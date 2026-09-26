package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import static io.github.markpollack.workflow.batch.durable.DurableWorkflows.*;

/** One-store child acceptance, waiting and settlement. All methods use the caller's transaction. */
final class LocalChildren {
    enum Transition {
        ACCEPTED("AFTER_CHILD_ACCEPT_COMMIT"), WAITING("AFTER_CHILD_WAIT_COMMIT"),
        SETTLED("AFTER_CHILD_SETTLEMENT_COMMIT"), LIMIT_EXCEEDED("");

        final String afterCommit;
        Transition(String afterCommit) { this.afterCommit = afterCommit; }
    }

    private final ApplicationDeployment deployment;
    private final ExecutionPolicy policy;
    private final BoundaryHooks hooks;

    LocalChildren(ApplicationDeployment deployment, ExecutionPolicy policy, BoundaryHooks hooks) {
        this.deployment = deployment;
        this.policy = policy;
        this.hooks = hooks;
    }

    Transition advance(JdbcRunStore.Tx tx, RunState parent, RunState.Invocation invocation,
            ValidatedWorkflow.InvocationRecipe recipe, ValidatedWorkflow workflow, ResolvedApplication resolved) throws Exception {
        Call call = new Call(parent, invocation, recipe, workflow, resolved);
        if (invocation.childId.isEmpty()) return accept(tx, call);
        RunState child = reattach(tx, call);
        if (child.active()) {
            releaseParent(tx, call);
            return Transition.WAITING;
        }
        settle(tx, call, child);
        return Transition.SETTLED;
    }

    /** The selected parent-call boundary and its exact input, checked before any transition. */
    private static final class Call {
        final RunState parent;
        final RunState.Invocation invocation;
        final ValidatedWorkflow workflow, childWorkflow;
        final ValidatedWorkflow.InvocationRecipe recipe;
        final RunState.Value input;
        final ResolvedApplication resolved;

        Call(RunState parent, RunState.Invocation invocation, ValidatedWorkflow.InvocationRecipe recipe,
                ValidatedWorkflow workflow, ResolvedApplication resolved) {
            this.parent = parent;
            this.invocation = invocation;
            this.recipe = recipe;
            this.workflow = workflow;
            this.childWorkflow = workflow.children().get(recipe.placement());
            this.resolved = resolved;
            input = parent.values.get(valueId(recipe.input()));
            verifyValue(input, workflow.values().get(recipe.input()));
            if (!invocation.id.equals(Digests.fields(parent.id, recipe.placement().graphName(), "root"))
                    || !invocation.placement.equals(recipe.placement().graphName())
                    || !invocation.input.equals(input.id) || !invocation.output.equals(valueId(recipe.output()))
                    || !invocation.status.equals("UNRESOLVED") || !invocation.deliveries.isEmpty() || invocation.settledAt != 0) {
                throw new WorkflowRefusal("CHILD_FACTS_CHANGED", "child invocation facts disagree");
            }
            // Parent preflight already resolved all selected children. Reuse its codec here.
            resolved.decode(input.payload, childWorkflow.definition().input());
        }
    }

    private Transition accept(JdbcRunStore.Tx tx, Call call) throws Exception {
        RunState parent = call.parent;
        RunState root = tx.get(parent.rootId);
        if (parent.depth >= root.maximumChildDepth || root.descendants >= root.maximumDescendants) {
            tx.terminal(parent, "FAILED", "CHILD_LIMIT_EXCEEDED", "finite child lineage allowance exhausted", "store");
            return Transition.LIMIT_EXCEEDED;
        }
        hooks.at("BEFORE_CHILD_RESERVATION", parent.id);
        if (tx.byKey("child:" + call.invocation.id) != null) {
            throw new WorkflowRefusal("CHILD_FACTS_CHANGED", "unattached child reservation already exists");
        }
        RunState child = admission(tx, call, root);
        root.descendants = Math.incrementExact(root.descendants);
        call.invocation.childId = child.id;
        parent.event("CHILD_ACCEPTED", tx.now, call.invocation.id + ":" + child.id);
        tx.save(root);
        tx.insert(child);
        hooks.at("AFTER_CHILD_CREATE_BEFORE_COMMIT", parent.id);
        releaseParent(tx, call);
        hooks.at("BEFORE_CHILD_ACCEPT_COMMIT", parent.id);
        return Transition.ACCEPTED;
    }

    private RunState admission(JdbcRunStore.Tx tx, Call call, RunState root) {
        RunState parent = call.parent;
        RunState child = new RunState();
        child.id = ChildAttachment.childId(parent, call.invocation);
        child.key = "child:" + call.invocation.id;
        child.compatibility = compatibility(call.childWorkflow, deployment.manifest(), policy);
        child.admission = Digests.fields(child.compatibility, call.input.digest);
        child.display = call.childWorkflow.definition().name();
        child.authored = call.childWorkflow.authoredIdentity();
        child.deployment = deployment.manifest();
        child.admitted = tx.now;
        var deadline = ChildAttachment.deadline(parent, tx.now, call.childWorkflow);
        child.deadline = deadline.instant();
        child.deadlineOrigin = deadline.origin();
        child.leaseMillis = parent.leaseMillis;
        child.maximumDeliveries = parent.maximumDeliveries;
        child.maximumChildDepth = root.maximumChildDepth;
        child.maximumDescendants = root.maximumDescendants;
        child.rootId = root.id;
        child.parentId = parent.id;
        child.parentInvocation = call.invocation.id;
        child.depth = parent.depth + 1;
        var rootRecipe = ChildAttachment.rootRecipe(call.childWorkflow);
        putValue(child, rootRecipe, call.input.payload, "");
        var input = child.values.get(valueId(rootRecipe.identity()));
        input.sourceRun = parent.id;
        input.sourceValue = call.input.id;
        child.event("ADMITTED", tx.now, child.admission);
        child.event("CHILD_ATTACHED", tx.now, parent.id + ":" + call.invocation.id);
        return child;
    }

    private RunState reattach(JdbcRunStore.Tx tx, Call call) throws Exception {
        if (!call.invocation.childId.equals(ChildAttachment.childId(call.parent, call.invocation))) {
            throw new WorkflowRefusal("CHILD_FACTS_CHANGED", "child identity changed");
        }
        RunState child = tx.get(call.invocation.childId);
        ChildAttachment.verify(tx, child, call.childWorkflow, deployment.manifest(), policy);
        tx.observe(child);
        return child;
    }

    private void settle(JdbcRunStore.Tx tx, Call call, RunState child) throws Exception {
        RunState parent = call.parent;
        parent.settleChild(call.invocation, child, tx.now);
        if (child.status.equals("SUCCEEDED")) {
            var outputRecipe = call.childWorkflow.values().get(call.childWorkflow.terminal().successValue());
            var output = child.values.get(child.output);
            verifyValue(output, outputRecipe);
            call.resolved.decode(output.payload, call.childWorkflow.definition().output());
            putValue(parent, call.workflow.values().get(call.recipe.output()), output.payload, call.invocation.id);
            var parentOutput = parent.values.get(call.invocation.output);
            parentOutput.sourceRun = child.id;
            parentOutput.sourceValue = output.id;
            call.invocation.status = "COMMITTED";
            parent.next++;
            if (parent.next == call.workflow.invocations().size()) finish(tx, parent, call.workflow);
            else tx.save(parent);
        } else {
            call.invocation.status = "FAILED";
            tx.terminal(parent, "FAILED", "CHILD_" + child.status, child.reasonCode + ":" + child.reasonMessage, child.id);
        }
        hooks.at("BEFORE_CHILD_SETTLEMENT_COMMIT", parent.id);
    }

    private static void releaseParent(JdbcRunStore.Tx tx, Call call) throws Exception {
        call.parent.generation = Math.incrementExact(call.parent.generation);
        call.parent.owner = "";
        call.parent.leaseUntil = 0;
        call.parent.event("WAITING_CHILD", tx.now, call.invocation.childId);
        tx.save(call.parent);
    }
}
