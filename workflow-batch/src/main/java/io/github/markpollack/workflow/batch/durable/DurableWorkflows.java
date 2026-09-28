package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * One local database owner for validated sequential workflows. Application-supplied steps
 * execute synchronously on the caller, outside transactions. No executor, lease or scheduler.
 * Process death releases ownership; compatible applications can recover unfinished progress.
 */
public final class DurableWorkflows implements AutoCloseable {
    private final JdbcRunStore store;
    private final StoreOwnership ownership;
    private final RuntimeLifecycle lifecycle = new RuntimeLifecycle();
    private final ApplicationDeployment deployment;
    private final ExecutionPolicy policy;
    private final BoundaryHooks hooks;

    /** Open exclusive management access without loading executable application objects. */
    public static DurableWorkflows open(Path database) { return open(database,ExecutionPolicy.DEFAULT); }
    public static DurableWorkflows open(Path database,ExecutionPolicy policy) { return new DurableWorkflows(database,null,policy,BoundaryHooks.NONE); }
    /** Open one runtime with a fixed registration of application-supplied steps. */
    public static DurableWorkflows open(Path database,ApplicationDeployment deployment) { return open(database,deployment,ExecutionPolicy.DEFAULT); }
    public static DurableWorkflows open(Path database,ApplicationDeployment deployment,ExecutionPolicy policy) {
        return new DurableWorkflows(database,Objects.requireNonNull(deployment),policy,BoundaryHooks.NONE);
    }
    DurableWorkflows(Path database,ApplicationDeployment deployment,ExecutionPolicy policy,BoundaryHooks hooks) {
        this.deployment=deployment; this.policy=Objects.requireNonNull(policy); this.hooks=Objects.requireNonNull(hooks);
        if (Thread.currentThread().isInterrupted()) throw new WorkflowRefusal("INTERRUPTED", "caller is already interrupted");
        ownership=StoreOwnership.acquire(database);
        try { store=new JdbcRunStore(ownership.database()); }
        catch (RuntimeException | Error ex) {
            try { ownership.close(); } catch (RuntimeException close) { ex.addSuppressed(close); }
            throw ex;
        }
    }

    /** Admit exact input, selections and deadline atomically; identical idempotency reuse returns the original run. */
    public RunSnapshot start(ValidatedWorkflow workflow,String idempotencyKey,Object input) {
        return start(workflow,idempotencyKey,input,workflow.definition().name());
    }
    /** Display text is an admitted presentation fact and does not change authored behavior or idempotency. */
    public RunSnapshot start(ValidatedWorkflow workflow,String idempotencyKey,Object input,String displayName) {
        try (var activity = lifecycle.enter(null)) {
            return admit(workflow, idempotencyKey, input, displayName);
        }
    }
    private RunSnapshot admit(ValidatedWorkflow workflow,String idempotencyKey,Object input,String displayName) {
        requireSequential(workflow);requireText(idempotencyKey,"idempotency key");requireText(displayName,"display name");
        ResolvedApplication resolved=new ResolvedApplication(requireDeployment(),workflow);
        byte[] root=resolved.encode(input,workflow.definition().input());
        resolved.decode(root,workflow.definition().input());
        String compatibility=compatibility(workflow,requireDeployment().manifest(),policy);
        String admission=Digests.fields(compatibility,Digests.of(root));
        RunSnapshot result=store.transaction(tx->{
            RunState existing=tx.byKey("root:"+idempotencyKey);
            if(existing!=null) {
                compatible(existing,workflow);
                if(!existing.admission.equals(admission)) throw new WorkflowRefusal("IDEMPOTENCY_CONFLICT","idempotency key has different admitted facts");
                tx.observe(existing);
                return existing.snapshot();
            }
            RunState run=new RunState();run.id=UUID.randomUUID().toString();run.key="root:"+idempotencyKey;run.admission=admission;
            run.compatibility=compatibility;run.display=displayName;run.authored=workflow.authoredIdentity();
            run.deployment=requireDeployment().manifest();
            run.admitted=tx.now;run.deadline=Math.addExact(tx.now,workflow.definition().deadline().toMillis());
            run.deadlineOrigin=workflow.deadlineOrigin();run.maximumAttempts=policy.maximumAttempts();
            var rootRecipe=workflow.values().values().stream().filter(v->v.identity().role().equals("root")).findFirst()
                    .orElseThrow(()->new WorkflowRefusal("BINDING_INVALID","compiler root recipe missing"));
            putValue(run,rootRecipe,root,"");
            run.event("ADMITTED",tx.now,run.admission);tx.insert(run);
            hooks.at("BEFORE_ADMISSION_COMMIT",run.id);return run.snapshot();
        });
        hooks.at("AFTER_ADMISSION_COMMIT",result.runId());return result;
    }

    /** Inspect durable facts; expiry is materialized on observation. No application step executes. */
    public RunSnapshot inspect(String runId) {
        try (var activity=lifecycle.enter(null)) { return inspectSaved(runId); }
    }
    private RunSnapshot inspectSaved(String runId) {
        return store.transaction(tx->{ RunState run=tx.get(runId); tx.observe(run); return run.snapshot(); });
    }
    /** Find unfinished runs. This is observation, not a background execution facility. */
    public List<RunSnapshot> discover() {
        try (var activity=lifecycle.enter(null)) {
            return store.transaction(tx->{
                List<RunSnapshot> result=new ArrayList<>();
                for (RunState run:tx.all()) { tx.observe(run); if(run.active()) result.add(run.snapshot()); }
                return List.copyOf(result);
            });
        }
    }
    /** Revoke durable continuation; does not interrupt running application code. */
    public RunSnapshot cancel(String runId,String actor,String reason) {
        try (var activity=lifecycle.enter(null)) {
            requireText(actor,"cancellation actor"); requireText(reason,"cancellation reason");
            return store.transaction(tx->{
                RunState run=tx.get(runId); tx.observe(run);
                if (run.active()) tx.terminal(run,"CANCELLED","CANCELLED",reason,actor);
                return run.snapshot();
            });
        }
    }

    /**
     * Continue synchronously to terminal using saved progress. A concurrent same-run call
     * refuses with RUN_BUSY before charging an attempt; different runs may execute concurrently.
     * An application interrupt returns saved progress after the current outcome, restoring the flag.
     * A terminal run is returned unchanged. This does not restart terminal failures.
     */
    public RunSnapshot resume(String runId,ValidatedWorkflow workflow) {
        requireText(runId,"run ID");
        try (var activity=lifecycle.enter(runId)) {
            ResolvedApplication resolved=resolve(runId,workflow);
            RunSnapshot result;
            do { result=advanceOwned(runId,workflow,resolved,activity); } while(!result.terminal() && !activity.interrupted());
            return result;
        }
    }

    /** Execute at most one step or engine boundary directly on the caller; no executor handoff. */
    public RunSnapshot advance(String runId,ValidatedWorkflow workflow) {
        requireText(runId,"run ID");
        try (var activity=lifecycle.enter(runId)) {
            return advanceOwned(runId,workflow,resolve(runId,workflow),activity);
        }
    }

    private RunSnapshot advanceOwned(String runId,ValidatedWorkflow workflow,ResolvedApplication resolved,
            RuntimeLifecycle.Activity activity) {
        // Preparation commits exact input and a charged attempt, never application code.
        Advancement prepared=store.transaction(tx->prepareAdvance(tx,runId,workflow,resolved));
        if (prepared instanceof Progress progress) return progress.snapshot;
        return executeAttempt(runId,workflow,resolved,(Dispatch)prepared,activity);
    }

    private Advancement prepareAdvance(JdbcRunStore.Tx tx,String runId,ValidatedWorkflow workflow,
            ResolvedApplication resolved) throws Exception {
        RunState run=tx.get(runId); compatible(run,workflow); tx.observe(run);
        if (!run.active()) return new Progress(run.snapshot());
        if (run.next==workflow.invocations().size()) {
            finish(tx,run,workflow); hooks.at("BEFORE_TERMINAL_COMMIT",run.id);
            return new Progress(run.snapshot());
        }
        var recipe=workflow.invocations().get(run.next);
        RunState.Invocation invocation;
        if (run.invocations.size()==run.next) {
            invocation=new RunState.Invocation(); invocation.id=invocationId(run.id,recipe.placement());
            invocation.placement=recipe.placement().graphName(); invocation.input=valueId(recipe.input()); invocation.output=valueId(recipe.output());
            try { materialize(tx,run,recipe.input(),workflow,resolved,invocation.id); }
            catch (WorkflowRefusal ex) {
                if (!ex.code().equals("INPUT_FAILED")&&!ex.code().equals("ENCODE_FAILED")) throw ex;
                tx.terminal(run,"FAILED","INPUT_ENCODING_FAILED",ex.getMessage(),"runtime");
                return new Progress(run.snapshot());
            }
            run.invocations.add(invocation);
        } else invocation=run.invocations.get(run.next);
        validateInvocation(run,invocation,recipe);
        return chargeAttempt(tx,run,invocation,recipe,workflow,resolved);
    }

    private Advancement chargeAttempt(JdbcRunStore.Tx tx,RunState run,RunState.Invocation invocation,
            ValidatedWorkflow.InvocationRecipe recipe,ValidatedWorkflow workflow,ResolvedApplication resolved) throws Exception {
        if (invocation.attempts.size()>=run.maximumAttempts) {
            tx.terminal(run,"FAILED","ATTEMPTS_EXHAUSTED","finite execution attempt allowance exhausted","runtime");
            return new Progress(run.snapshot());
        }
        for (var previous:invocation.attempts) if(previous.disposition.equals("UNRESOLVED")) previous.disposition="REATTEMPTED";
        RunState.Attempt attempt=new RunState.Attempt(); attempt.id=UUID.randomUUID().toString();
        attempt.number=invocation.attempts.size()+1; attempt.charged=tx.now;
        Object input=decode(tx,run,recipe.input(),workflow,resolved);
        invocation.attempts.add(attempt); run.event("DISPATCHED",tx.now,invocation.id+":"+attempt.id);
        tx.save(run); hooks.at("BEFORE_DISPATCH_COMMIT",run.id);
        StepContext context=new StepContext(run.id,invocation.id,attempt.id,attempt.number,
                Instant.ofEpochMilli(run.deadline),requireDeployment().configuration());
        return new Dispatch(recipe,input,context);
    }

    private RunSnapshot executeAttempt(String runId,ValidatedWorkflow workflow,ResolvedApplication resolved,
            Dispatch dispatch,RuntimeLifecycle.Activity activity) {
        hooks.at("AFTER_DISPATCH_COMMIT",runId);
        boolean mayEnter=store.transaction(tx->{
            RunState run=tx.get(runId); compatible(run,workflow); tx.observe(run);
            if(!run.active()) return false;
            current(run,dispatch.context,dispatch.recipe); return true;
        });
        if(!mayEnter) return inspectSaved(runId);
        byte[] output=null;
        Throwable failure=null;
        try {
            // The supplied object and its dependencies belong to the application, not the store.
            Object value=resolved.execute(dispatch.recipe.executable().entryPoint(),dispatch.input,dispatch.context);
            output=resolved.encode(value,workflow.values().get(dispatch.recipe.output()).declaration());
        } catch (Exception | LinkageError ex) { failure=ex; }
        finally { activity.captureInterrupt(failure instanceof InterruptedException); }
        if(failure!=null) {
            String code=failure instanceof WorkflowRefusal refusal&&refusal.code().equals("ENCODE_FAILED")?"OUTPUT_ENCODING_FAILED":"STEP_FAILED";
            return fail(runId,workflow,dispatch,code,failure.getClass().getName()+": "+String.valueOf(failure.getMessage()));
        }
        hooks.at("AFTER_HANDLER_RETURN",runId);
        byte[] committedOutput=output;
        RunSnapshot result=store.transaction(tx->commitResult(tx,runId,workflow,dispatch,committedOutput));
        hooks.at("AFTER_RESULT_COMMIT",runId);
        return result;
    }

    private RunSnapshot commitResult(JdbcRunStore.Tx tx,String runId,ValidatedWorkflow workflow,
            Dispatch dispatch,byte[] output) throws Exception {
        RunState run=tx.get(runId); compatible(run,workflow); tx.observe(run);
        if(!run.active()) return run.snapshot();
        RunState.Invocation invocation=current(run,dispatch.context,dispatch.recipe);
        putValue(run,workflow.values().get(dispatch.recipe.output()),output,invocation.id);
        invocation.status="COMMITTED"; invocation.attempts.getLast().disposition="COMMITTED";
        run.next++; run.event("RESULT_COMMITTED",tx.now,invocation.id+":"+invocation.output);
        if(run.next==workflow.invocations().size()) finish(tx,run,workflow); else tx.save(run);
        hooks.at("BEFORE_RESULT_COMMIT",run.id); return run.snapshot();
    }

    /** Decode the accepted result using the same compatible supplied application contract. */
    public Object result(String runId,ValidatedWorkflow workflow) {
        try (var activity=lifecycle.enter(null)) {
            ResolvedApplication resolved=resolve(runId,workflow);
            byte[] bytes=store.transaction(tx->{
                RunState run=tx.get(runId); compatible(run,workflow);
                if(!run.status.equals("SUCCEEDED")) throw new WorkflowRefusal("NO_SUCCESS_RESULT","run has no successful terminal result");
                RunState.Value value=verifiedValue(tx,run,workflow.terminal().successValue(),workflow);
                if(!run.output.equals(value.id)) throw new WorkflowRefusal("VALUE_CHANGED","terminal output identity differs");
                return value.payload.clone();
            });
            return resolved.decode(bytes,workflow.definition().output());
        }
    }
    private RunSnapshot fail(String runId,ValidatedWorkflow workflow,Dispatch dispatch,String code,String message) {
        return store.transaction(tx->{
            RunState run=tx.get(runId); compatible(run,workflow); tx.observe(run);
            if(!run.active()) return run.snapshot();
            current(run,dispatch.context,dispatch.recipe); tx.terminal(run,"FAILED",code,message,"runtime"); return run.snapshot();
        });
    }
    private ApplicationDeployment requireDeployment() {
        if(deployment==null) throw new WorkflowRefusal("DEPLOYMENT_REQUIRED","execution requires application step registration");
        return deployment;
    }
    private ResolvedApplication resolve(String runId,ValidatedWorkflow workflow) {
        requireSequential(workflow);
        ApplicationDeployment supplied=requireDeployment();
        store.transaction(tx->{ compatible(tx.get(runId),workflow); return null; });
        return new ResolvedApplication(supplied,workflow);
    }
    private static void requireSequential(ValidatedWorkflow workflow) {
        Objects.requireNonNull(workflow);
        if (!workflow.capabilities().equals(Set.of(Capability.OPERATION,Capability.TERMINAL)))
            throw new WorkflowRefusal("UNSUPPORTED_CAPABILITY","this runtime admits sequential steps and terminals only");
    }
    private static RunState.Invocation current(RunState run,StepContext context,ValidatedWorkflow.InvocationRecipe recipe) {
        if(run.next>=run.invocations.size()) throw new WorkflowRefusal("ATTEMPT_CHANGED","no unresolved invocation");
        RunState.Invocation call=run.invocations.get(run.next);
        validateInvocation(run,call,recipe);
        if(!call.id.equals(context.invocationId())||call.attempts.isEmpty()
                ||!call.attempts.getLast().id.equals(context.attemptId()))
            throw new WorkflowRefusal("ATTEMPT_CHANGED","current execution attempt differs");
        return call;
    }
    private static void validateInvocation(RunState run,RunState.Invocation call,ValidatedWorkflow.InvocationRecipe recipe) {
        if(!invocationId(run.id,recipe.placement()).equals(call.id)
                ||!recipe.placement().graphName().equals(call.placement)||!valueId(recipe.input()).equals(call.input)
                ||!valueId(recipe.output()).equals(call.output)||!"UNRESOLVED".equals(call.status))
            throw new WorkflowRefusal("INVOCATION_CHANGED","saved invocation differs or is already committed");
    }
    static void finish(JdbcRunStore.Tx tx,RunState run,ValidatedWorkflow workflow) throws Exception {
        var terminal=workflow.terminal();
        if(terminal.intent()==Terminal.SUCCEEDED) {
            run.output=valueId(terminal.successValue());
            if(!run.values.containsKey(run.output)) throw new WorkflowRefusal("VALUE_MISSING","successful terminal value missing");
        }
        tx.terminal(run,terminal.intent().name(),"AUTHORED_"+terminal.intent().name(),terminal.reason(),"workflow");
    }
    private void materialize(JdbcRunStore.Tx tx,RunState run,ValueId id,ValidatedWorkflow workflow,ResolvedApplication resolved,String producer) throws Exception {
        if(run.values.containsKey(valueId(id))) { verifiedValue(tx,run,id,workflow);return; }
        var recipe=workflow.values().get(id);
        if(recipe==null||recipe.components().isEmpty()) throw new WorkflowRefusal("VALUE_MISSING","selected immutable value missing: "+id);
        List<Object> components=new ArrayList<>();
        for(ValueId component:recipe.components()) {
            // Components are already selected historical facts. A missing saved input must never be rebuilt.
            components.add(decode(tx,run,component,workflow,resolved));
        }
        Object input=resolved.assemble(recipe.declaration(),components);
        putValue(run,recipe,resolved.encode(input,recipe.declaration()),producer);
    }
    private Object decode(JdbcRunStore.Tx tx,RunState run,ValueId id,ValidatedWorkflow workflow,ResolvedApplication resolved) throws Exception {
        RunState.Value value=verifiedValue(tx,run,id,workflow);
        return resolved.decode(value.payload,workflow.values().get(id).declaration());
    }
    private RunState.Value verifiedValue(JdbcRunStore.Tx tx,RunState run,ValueId id,ValidatedWorkflow workflow) {
        RunState.Value value=run.values.get(valueId(id));
        verifyValue(value,workflow.values().get(id));
        return value;
    }
    static void verifyValue(RunState.Value value,ValidatedWorkflow.ValueRecipe recipe) {
        if(value==null||recipe==null) throw new WorkflowRefusal("VALUE_MISSING","committed selected value is missing");
        if(!value.id.equals(valueId(recipe.identity()))||!value.type.equals(recipe.contract().javaType())
                ||!value.shape.equals(recipe.contract().shapeDigest())||!value.codec.equals(codec(recipe.contract().codec()))
                ||!value.digest.equals(Digests.of(value.payload))
                ||!value.components.equals(recipe.components().stream().map(DurableWorkflows::valueId).toList())
                ||!value.consumed.equals(recipe.consumed().stream().map(DurableWorkflows::valueId).toList()))
            throw new WorkflowRefusal("VALUE_CHANGED","persisted value identity/type/codec/provenance differs");
    }
    static void putValue(RunState run,ValidatedWorkflow.ValueRecipe recipe,byte[] bytes,String producer) {
        RunState.Value value=new RunState.Value();value.id=valueId(recipe.identity());value.type=recipe.contract().javaType();
        value.shape=recipe.contract().shapeDigest();value.codec=codec(recipe.contract().codec());value.digest=Digests.of(bytes);
        value.payload=bytes.clone();value.producer=producer;value.components=recipe.components().stream().map(DurableWorkflows::valueId).toList();
        value.consumed=recipe.consumed().stream().map(DurableWorkflows::valueId).toList();
        if(run.values.putIfAbsent(value.id,value)!=null) throw new WorkflowRefusal("VALUE_IMMUTABLE","cannot overwrite immutable value");
    }
    static String valueId(ValueId id) { return Digests.fields(id.placement().graphName(),id.role(),id.phase()); }
    private static String invocationId(String run,Placement placement) { return Digests.fields(run,placement.graphName(),"root"); }
    private static String codec(TypeContracts.CodecIdentity codec) { return Digests.fields(codec.name(),codec.version(),codec.configuration()); }
    static String compatibility(ValidatedWorkflow workflow,ApplicationDeployment.Manifest manifest,ExecutionPolicy policy) {
        StringBuilder entries=new StringBuilder();
        for(var call:workflow.invocations()) entries.append(Digests.fields(call.placement().graphName(),call.executable().entryPoint(),
                call.executable().deploymentManifestDigest(),call.executable().configurationDigest()));
        return Digests.fields("durable-run-v4",workflow.authoredIdentity(),entries.toString(),manifest.identity(),
                codec(workflow.codecIdentity()),policy.identity());
    }
    private void compatible(RunState run,ValidatedWorkflow workflow) {
        ApplicationDeployment.Manifest supplied=requireDeployment().manifest();
        if(!supplied.equals(run.deployment)) throw new WorkflowRefusal("COMPATIBILITY","supplied deployment/configuration/codec/runtime differs from admitted manifest");
        if(!run.compatibility.equals(compatibility(workflow,supplied,policy)))
            throw new WorkflowRefusal("COMPATIBILITY","authored behavior, step selection or execution policy differs");
    }
    private static void requireText(String text,String field) { if(text==null||text.isBlank()) throw new IllegalArgumentException(field+" required"); }
    private sealed interface Advancement permits Dispatch,Progress {}
    private record Dispatch(ValidatedWorkflow.InvocationRecipe recipe,Object input,StepContext context) implements Advancement {}
    private record Progress(RunSnapshot snapshot) implements Advancement {}

    /**
     * Stop accepting calls and wait up to the supplied duration for active calls to finish.
     * False means shutdown is incomplete: store and ownership remain held; call again after
     * application work returns. Never interrupts a step, closes its dependencies, or cancels a run.
     */
    public boolean shutdown(Duration timeout) {
        return lifecycle.shutdown(timeout,()->{ store.close(); ownership.close(); });
    }
    /** Drain for up to thirty seconds; incomplete drainage refuses while retaining ownership. */
    @Override public void close() {
        if(!shutdown(Duration.ofSeconds(30))) throw new WorkflowRefusal("SHUTDOWN_INCOMPLETE","application calls remain active; ownership retained");
    }
}
