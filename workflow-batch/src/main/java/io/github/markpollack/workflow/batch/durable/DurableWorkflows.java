package io.github.markpollack.workflow.batch.durable;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Local durable lifecycle for compiler-validated sequential workflows. Start admits without
 * dispatch; discover finds admitted work; resume runs with finite renewable ownership. The
 * same database file can be reopened after process death. Concurrent handles are supported
 * within one JVM; embedded H2 excludes simultaneous writers in different JVMs.
 */
public final class DurableWorkflows implements AutoCloseable {
    private final JdbcRunStore store;
    private final ApplicationDeployment deployment;
    private final ExecutionPolicy policy;
    private final BoundaryHooks hooks;

    /** Open a management-only handle for inspection, discovery and cancellation. */
    public static DurableWorkflows open(Path database) { return open(database,ExecutionPolicy.DEFAULT); }
    public static DurableWorkflows open(Path database,ExecutionPolicy policy) { return new DurableWorkflows(database,null,policy,BoundaryHooks.NONE); }
    /** Open with one immutable registration used for both compatibility checks and invocation. */
    public static DurableWorkflows open(Path database,ApplicationDeployment deployment) { return open(database,deployment,ExecutionPolicy.DEFAULT); }
    public static DurableWorkflows open(Path database,ApplicationDeployment deployment,ExecutionPolicy policy) {
        return new DurableWorkflows(database,Objects.requireNonNull(deployment),policy,BoundaryHooks.NONE);
    }
    DurableWorkflows(Path database,ApplicationDeployment deployment,ExecutionPolicy policy,BoundaryHooks hooks) {
        this.deployment=deployment;this.policy=Objects.requireNonNull(policy);this.hooks=Objects.requireNonNull(hooks);store=new JdbcRunStore(database);
    }

    /** Admit exact input, selections and deadline atomically; identical idempotency reuse returns the original run. */
    public RunSnapshot start(ValidatedWorkflow workflow,String idempotencyKey,Object input) {
        return start(workflow,idempotencyKey,input,workflow.definition().name());
    }
    /** Display text is an admitted presentation fact and does not change authored behavior or idempotency. */
    public RunSnapshot start(ValidatedWorkflow workflow,String idempotencyKey,Object input,String displayName) {
        Objects.requireNonNull(workflow);requireText(idempotencyKey,"idempotency key");requireText(displayName,"display name");
        ResolvedApplication resolved=new ResolvedApplication(requireDeployment(),workflow);
        byte[] root=resolved.encode(input,workflow.definition().input());
        resolved.decode(root,workflow.definition().input());
        String compatibility=compatibility(workflow,requireDeployment().manifest(),policy);
        String admission=Digests.fields(compatibility,Digests.of(root));
        RunSnapshot result=store.transaction(tx->{
            RunState existing=tx.byKey(idempotencyKey);
            if(existing!=null) {
                compatible(existing,workflow);
                if(!existing.admission.equals(admission)) throw new WorkflowRefusal("IDEMPOTENCY_CONFLICT","idempotency key has different admitted facts");
                if(existing.expire(tx.now)) tx.save(existing);
                return existing.snapshot();
            }
            RunState run=new RunState();run.id=UUID.randomUUID().toString();run.key=idempotencyKey;run.admission=admission;
            run.compatibility=compatibility;run.display=displayName;run.authored=workflow.authoredIdentity();
            run.deployment=requireDeployment().manifest();
            run.admitted=tx.now;run.deadline=Math.addExact(tx.now,workflow.definition().deadline().toMillis());
            run.deadlineOrigin=workflow.deadlineOrigin();run.leaseMillis=policy.lease().toMillis();run.maximumDeliveries=policy.maximumDeliveries();
            var rootRecipe=workflow.values().values().stream().filter(v->v.identity().role().equals("root")).findFirst()
                    .orElseThrow(()->new WorkflowRefusal("BINDING_INVALID","compiler root recipe missing"));
            putValue(run,rootRecipe,root,"");
            run.event("ADMITTED",tx.now,run.admission);tx.insert(run);
            hooks.at("BEFORE_ADMISSION_COMMIT",run.id);return run.snapshot();
        });
        hooks.at("AFTER_ADMISSION_COMMIT",result.runId());return result;
    }

    /** Inspect without loading application code. Expiry is materialized on observation. */
    public RunSnapshot inspect(String runId) {
        return store.transaction(tx->{ RunState run=tx.get(runId);if(run.expire(tx.now)) tx.save(run);return run.snapshot(); });
    }

    /** Discover active admissions, including never-dispatched and expired-lease work; materialize run expiry. */
    public List<RunSnapshot> discover() {
        return store.transaction(tx->{
            List<RunSnapshot> result=new ArrayList<>();
            for(RunState run:tx.all()) { if(run.expire(tx.now)) tx.save(run);if(run.active()) result.add(run.snapshot()); }
            return List.copyOf(result);
        });
    }

    /** Store-linearized cancellation with actor/reason/time. The first valid terminal outcome is permanent. */
    public RunSnapshot cancel(String runId,String actor,String reason) {
        requireText(actor,"cancellation actor");requireText(reason,"cancellation reason");
        return store.transaction(tx->{
            RunState run=tx.get(runId);
            if(run.expire(tx.now)) tx.save(run);
            else if(run.active()) { run.terminal("CANCELLED","CANCELLED",reason,actor,tx.now);tx.save(run); }
            return run.snapshot();
        });
    }

    /** Continue under the original pinned selections, renewing ownership while the handler executes. */
    public RunSnapshot resume(String runId,ValidatedWorkflow workflow) {
        return resume(runId,workflow,"worker-"+UUID.randomUUID());
    }
    public RunSnapshot resume(String runId,ValidatedWorkflow workflow,String worker) {
        resolve(runId,workflow);
        RunSnapshot initial=inspect(runId);
        if(initial.terminal()) return initial;
        Lease lease=claim(runId,workflow,worker);
        ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"workflow-lease-renewal");t.setDaemon(true);return t;
        });
        long interval=Math.max(1,policy.lease().toMillis()/3);
        ScheduledFuture<?> renewal=heartbeat.scheduleWithFixedDelay(()->{
            try { renew(lease); } catch(WorkflowRefusal ignored) { /* Dispatch/commit independently guard current authority. */ }
        },interval,interval,TimeUnit.MILLISECONDS);
        try {
            RunSnapshot result;
            do { result=advance(lease,workflow); } while(!result.terminal());
            return result;
        } finally {
            // Interrupting embedded-store I/O can close its shared file channel. Drain renewal instead.
            renewal.cancel(false);heartbeat.shutdown();
            boolean interrupted=false;
            try {
                while(!heartbeat.isTerminated()) {
                    try { heartbeat.awaitTermination(1,TimeUnit.SECONDS); }
                    catch(InterruptedException ex) { interrupted=true; }
                }
            } finally { if(interrupted)Thread.currentThread().interrupt(); }
        }
    }

    /** An unforgeable ownership token; its generation is distinct from delivery and invocation IDs. */
    public static final class Lease {
        private final String runId,owner;
        private final long generation;
        private final ValidatedWorkflow workflow;
        private Lease(String runId,String owner,long generation,ValidatedWorkflow workflow) { this.runId=runId;this.owner=owner;this.generation=generation;this.workflow=workflow; }
        public String runId() { return runId; }
        public String owner() { return owner; }
        public long generation() { return generation; }
    }

    /** Claim unowned/expired work. A live owner cannot be displaced, even by reusing its display name. */
    public Lease claim(String runId,ValidatedWorkflow workflow,String worker) {
        requireText(worker,"worker");resolve(runId,workflow);
        Lease result=store.transaction(tx->{
            RunState run=tx.get(runId);compatible(run,workflow);
            if(run.expire(tx.now)) { tx.save(run);return null; }
            if(!run.active()) return null;
            if(!run.owner.isEmpty()&&tx.now<run.leaseUntil) return null;
            run.generation=Math.incrementExact(run.generation);run.owner=worker;
            run.leaseUntil=Math.min(run.deadline,Math.addExact(tx.now,run.leaseMillis));
            run.event("CLAIMED",tx.now,worker+":"+run.generation);tx.save(run);
            return new Lease(run.id,worker,run.generation,workflow);
        });
        if(result==null) throw new WorkflowRefusal("NOT_CLAIMABLE","run is terminal or has a live owner");
        return result;
    }

    /** Renew a still-live lease without extending the absolute run deadline or changing delivery accounting. */
    public RunSnapshot renew(Lease lease) {
        resolve(lease.runId,lease.workflow);
        RunSnapshot result=store.transaction(tx->{
            RunState run=tx.get(lease.runId);compatible(run,lease.workflow);
            hooks.at("AFTER_RENEWAL_READ",run.id);
            if(!eligible(tx,run,lease)) return null;
            run.leaseUntil=Math.min(run.deadline,Math.addExact(tx.now,run.leaseMillis));
            run.event("RENEWED",tx.now,lease.owner+":"+lease.generation);tx.save(run);return run.snapshot();
        });
        if(result==null) throw new WorkflowRefusal("FENCED","lease renewal has no current authority");
        return result;
    }

    /** Execute at most one physical delivery and commit its result, or commit an explicit terminal. */
    public RunSnapshot advance(Lease lease,ValidatedWorkflow workflow) {
        ResolvedApplication resolved=resolve(lease.runId,workflow);
        Dispatch dispatch=store.transaction(tx->{
            RunState run=tx.get(lease.runId);compatible(run,workflow);
            if(!eligible(tx,run,lease)) return new Dispatch(null,null,null,run.snapshot());
            if(run.next==workflow.invocations().size()) {
                finish(run,workflow,tx.now);tx.save(run);hooks.at("BEFORE_TERMINAL_COMMIT",run.id);
                return new Dispatch(null,null,null,run.snapshot());
            }
            var recipe=workflow.invocations().get(run.next);
            RunState.Invocation invocation;
            if(run.invocations.size()==run.next) {
                invocation=new RunState.Invocation();invocation.id=invocationId(run.id,recipe.placement());
                invocation.placement=recipe.placement().graphName();invocation.input=valueId(recipe.input());invocation.output=valueId(recipe.output());
                try { materialize(run,recipe.input(),workflow,resolved,invocation.id); }
                catch(WorkflowRefusal ex) {
                    if(ex.code().equals("DECODE_FAILED")||ex.code().equals("VALUE_CHANGED")||ex.code().equals("VALUE_MISSING")) throw ex;
                    run.terminal("FAILED","INPUT_ENCODING_FAILED",ex.getMessage(),lease.owner,tx.now);tx.save(run);
                    return new Dispatch(null,null,null,run.snapshot());
                }
                run.invocations.add(invocation);
            } else invocation=run.invocations.get(run.next);
            // A generation can charge this logical invocation at most once, even if a caller retries advance.
            if(invocation.deliveries.stream().anyMatch(d->d.generation==lease.generation))
                throw new WorkflowRefusal("DELIVERY_IN_FLIGHT","this generation already dispatched the unresolved invocation");
            if(invocation.deliveries.size()>=run.maximumDeliveries) {
                run.terminal("FAILED","DELIVERY_EXHAUSTED","finite physical delivery allowance exhausted",lease.owner,tx.now);tx.save(run);
                return new Dispatch(null,null,null,run.snapshot());
            }
            for(var previous:invocation.deliveries) if(previous.disposition.equals("UNRESOLVED")) previous.disposition="REDELIVERED";
            RunState.Delivery delivery=new RunState.Delivery();delivery.id=UUID.randomUUID().toString();
            delivery.number=invocation.deliveries.size()+1;delivery.generation=lease.generation;delivery.charged=tx.now;
            Object input=decode(run,recipe.input(),workflow,resolved);
            invocation.deliveries.add(delivery);run.event("DISPATCHED",tx.now,invocation.id+":"+delivery.id);
            tx.save(run);hooks.at("BEFORE_DISPATCH_COMMIT",run.id);
            DeliveryContext context=new DeliveryContext(run.id,invocation.id,delivery.id,lease.generation,delivery.number,Instant.ofEpochMilli(run.deadline));
            return new Dispatch(recipe,input,context,null);
        });
        if(dispatch.context==null) {
            if(dispatch.snapshot.terminal()) return dispatch.snapshot;
            throw new WorkflowRefusal("FENCED","dispatch has no current authority");
        }
        hooks.at("AFTER_DISPATCH_COMMIT",lease.runId);
        // Recheck expiry/cancellation/lease after any delay between the committed charge and entry.
        boolean mayEnter=store.transaction(tx->{RunState run=tx.get(lease.runId);compatible(run,workflow);return eligible(tx,run,lease);});
        if(!mayEnter) return inspect(lease.runId);
        byte[] output;
        try {
            Object value=resolved.execute(dispatch.recipe.executable().entryPoint(),dispatch.input,dispatch.context);
            output=resolved.encode(value,workflow.values().get(dispatch.recipe.output()).declaration());
        } catch(Exception|LinkageError ex) {
            String code=ex instanceof WorkflowRefusal refusal&&refusal.code().equals("ENCODE_FAILED")?"OUTPUT_ENCODING_FAILED":"HANDLER_FAILED";
            return fail(lease,dispatch.context,code,ex.getClass().getName()+": "+String.valueOf(ex.getMessage()));
        }
        hooks.at("AFTER_HANDLER_RETURN",lease.runId);
        RunSnapshot result=store.transaction(tx->{
            RunState run=tx.get(lease.runId);compatible(run,workflow);
            if(!eligible(tx,run,lease)) return run.snapshot();
            RunState.Invocation invocation=current(run,dispatch.context);
            putValue(run,workflow.values().get(dispatch.recipe.output()),output,invocation.id);
            invocation.status="COMMITTED";invocation.deliveries.getLast().disposition="COMMITTED";
            run.next++;run.event("RESULT_COMMITTED",tx.now,invocation.id+":"+invocation.output);
            if(run.next==workflow.invocations().size()) finish(run,workflow,tx.now);
            tx.save(run);hooks.at("BEFORE_RESULT_COMMIT",run.id);return run.snapshot();
        });
        hooks.at("AFTER_RESULT_COMMIT",lease.runId);return result;
    }

    /** Decode a successful result into the consumer's original declared Java contract. */
    public Object result(String runId,ValidatedWorkflow workflow) {
        ResolvedApplication resolved=resolve(runId,workflow);
        byte[] bytes=store.transaction(tx->{
            RunState run=tx.get(runId);compatible(run,workflow);
            if(!run.status.equals("SUCCEEDED")) throw new WorkflowRefusal("NO_SUCCESS_RESULT","run has no successful terminal result");
            RunState.Value value=run.values.get(run.output);verifyValue(value,workflow.values().get(workflow.terminal().successValue()));return value.payload.clone();
        });
        return resolved.decode(bytes,workflow.definition().output());
    }

    private RunSnapshot fail(Lease lease,DeliveryContext context,String code,String message) {
        return store.transaction(tx->{
            RunState run=tx.get(lease.runId);compatible(run,lease.workflow);
            if(!eligible(tx,run,lease)) return run.snapshot();
            current(run,context);run.terminal("FAILED",code,message,lease.owner,tx.now);tx.save(run);return run.snapshot();
        });
    }
    private ApplicationDeployment requireDeployment() {
        if(deployment==null) throw new WorkflowRefusal("DEPLOYMENT_REQUIRED","execution requires an application deployment registration");
        return deployment;
    }
    private ResolvedApplication resolve(String runId,ValidatedWorkflow workflow) {
        ApplicationDeployment supplied=requireDeployment();
        store.transaction(tx->{compatible(tx.get(runId),workflow);return null;});
        return new ResolvedApplication(supplied,workflow);
    }
    private boolean eligible(JdbcRunStore.Tx tx,RunState run,Lease lease) throws Exception {
        if(run.expire(tx.now)) { tx.save(run);return false; }
        return run.active()&&run.generation==lease.generation&&run.owner.equals(lease.owner)&&tx.now<run.leaseUntil;
    }
    private static RunState.Invocation current(RunState run,DeliveryContext context) {
        if(run.next>=run.invocations.size()) throw new WorkflowRefusal("FENCED","no unresolved invocation");
        RunState.Invocation call=run.invocations.get(run.next);
        if(!call.id.equals(context.invocationId())||!call.status.equals("UNRESOLVED")
                ||!call.deliveries.getLast().id.equals(context.deliveryId())) throw new WorkflowRefusal("FENCED","delivery replaced or committed");
        return call;
    }
    private static void finish(RunState run,ValidatedWorkflow workflow,long now) {
        var terminal=workflow.terminal();
        if(terminal.intent()==Terminal.SUCCEEDED) {
            run.output=valueId(terminal.successValue());
            if(!run.values.containsKey(run.output)) throw new WorkflowRefusal("VALUE_MISSING","successful terminal value missing");
        }
        run.terminal(terminal.intent().name(),"AUTHORED_"+terminal.intent().name(),terminal.reason(),"workflow",now);
    }
    private static void materialize(RunState run,ValueId id,ValidatedWorkflow workflow,ResolvedApplication resolved,String producer) {
        if(run.values.containsKey(valueId(id))) { verifyValue(run.values.get(valueId(id)),workflow.values().get(id));return; }
        var recipe=workflow.values().get(id);
        if(recipe==null||recipe.components().isEmpty()) throw new WorkflowRefusal("VALUE_MISSING","selected immutable value missing: "+id);
        List<Object> components=new ArrayList<>();
        for(ValueId component:recipe.components()) {
            // Components are already selected historical facts. A missing saved input must never be rebuilt.
            components.add(decode(run,component,workflow,resolved));
        }
        Object input=resolved.assemble(recipe.declaration(),components);
        putValue(run,recipe,resolved.encode(input,recipe.declaration()),producer);
    }
    private static Object decode(RunState run,ValueId id,ValidatedWorkflow workflow,ResolvedApplication resolved) {
        RunState.Value value=run.values.get(valueId(id));var recipe=workflow.values().get(id);verifyValue(value,recipe);
        return resolved.decode(value.payload,recipe.declaration());
    }
    private static void verifyValue(RunState.Value value,ValidatedWorkflow.ValueRecipe recipe) {
        if(value==null||recipe==null) throw new WorkflowRefusal("VALUE_MISSING","committed selected value is missing");
        if(!value.id.equals(valueId(recipe.identity()))||!value.type.equals(recipe.contract().javaType())
                ||!value.shape.equals(recipe.contract().shapeDigest())||!value.codec.equals(codec(recipe.contract().codec()))
                ||!value.digest.equals(Digests.of(value.payload))
                ||!value.components.equals(recipe.components().stream().map(DurableWorkflows::valueId).toList())
                ||!value.consumed.equals(recipe.consumed().stream().map(DurableWorkflows::valueId).toList()))
            throw new WorkflowRefusal("VALUE_CHANGED","persisted value identity/type/codec/provenance differs");
    }
    private static void putValue(RunState run,ValidatedWorkflow.ValueRecipe recipe,byte[] bytes,String producer) {
        RunState.Value value=new RunState.Value();value.id=valueId(recipe.identity());value.type=recipe.contract().javaType();
        value.shape=recipe.contract().shapeDigest();value.codec=codec(recipe.contract().codec());value.digest=Digests.of(bytes);
        value.payload=bytes.clone();value.producer=producer;value.components=recipe.components().stream().map(DurableWorkflows::valueId).toList();
        value.consumed=recipe.consumed().stream().map(DurableWorkflows::valueId).toList();
        if(run.values.putIfAbsent(value.id,value)!=null) throw new WorkflowRefusal("VALUE_IMMUTABLE","cannot overwrite immutable value");
    }
    static String valueId(ValueId id) { return Digests.fields(id.placement().graphName(),id.role(),id.phase()); }
    private static String invocationId(String run,Placement placement) { return Digests.fields(run,placement.graphName(),"root"); }
    private static String codec(TypeContracts.CodecIdentity codec) { return Digests.fields(codec.name(),codec.version(),codec.configuration()); }
    private static String compatibility(ValidatedWorkflow workflow,ApplicationDeployment.Manifest manifest,ExecutionPolicy policy) {
        StringBuilder entries=new StringBuilder();
        for(var call:workflow.invocations()) entries.append(Digests.fields(call.placement().graphName(),call.executable().entryPoint(),
                call.executable().deploymentManifestDigest(),call.executable().configurationDigest()));
        return Digests.fields("durable-run-v2",workflow.authoredIdentity(),entries.toString(),manifest.identity(),
                codec(workflow.codecIdentity()),policy.identity());
    }
    private void compatible(RunState run,ValidatedWorkflow workflow) {
        ApplicationDeployment.Manifest supplied=requireDeployment().manifest();
        if(!supplied.equals(run.deployment))
            throw new WorkflowRefusal("COMPATIBILITY","supplied deployment/configuration/codec/runtime differs from admitted manifest");
        if(!run.compatibility.equals(compatibility(workflow,supplied,policy)))
            throw new WorkflowRefusal("COMPATIBILITY","authored behavior, operation selection or execution policy differs");
    }
    private static void requireText(String text,String field) { if(text==null||text.isBlank()) throw new IllegalArgumentException(field+" required"); }
    private record Dispatch(ValidatedWorkflow.InvocationRecipe recipe,Object input,DeliveryContext context,RunSnapshot snapshot) {}
    @Override public void close() { store.close(); }
}
