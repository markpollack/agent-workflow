package io.github.markpollack.workflow.batch.durable;

import java.util.Arrays;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import static io.github.markpollack.workflow.batch.durable.DurableWorkflows.*;

/** Reciprocal run, call and value facts required by either side of an accepted child boundary. */
final class ChildAttachment {
    private ChildAttachment() {}

    static String childId(RunState parent, RunState.Invocation call) {
        return Digests.fields("local-child-v1", parent.id, call.id);
    }

    static ValidatedWorkflow.ValueRecipe rootRecipe(ValidatedWorkflow workflow) {
        return workflow.values().values().stream().filter(v -> v.identity().role().equals("root"))
                .findFirst().orElseThrow(() -> new WorkflowRefusal("BINDING_INVALID", "child root recipe missing"));
    }

    record Deadline(long instant, String origin) {}

    static Deadline deadline(RunState parent, long admitted, ValidatedWorkflow workflow) {
        long local = Math.addExact(admitted, workflow.definition().deadline().toMillis());
        return parent.deadline <= local
                ? new Deadline(parent.deadline, "INHERITED:" + parent.id + ":" + parent.deadlineOrigin)
                : new Deadline(local, workflow.deadlineOrigin());
    }

    /** Read-only checks in the caller's transaction, before any child charge or application entry. */
    static void verify(JdbcRunStore.Tx tx, RunState child, ValidatedWorkflow workflow,
            ApplicationDeployment.Manifest deployment, ExecutionPolicy policy) throws Exception {
        if (child.parentId.isEmpty()) {
            if (!child.rootId.equals(child.id) || !child.parentInvocation.isEmpty() || child.depth != 0) {
                throw changed("root lineage disagrees");
            }
            return;
        }
        RunState parent = tx.get(child.parentId);
        RunState.Invocation call = parent.invocations.stream()
                .filter(candidate -> candidate.id.equals(child.parentInvocation)).findFirst()
                .orElseThrow(() -> changed("accepted parent invocation missing"));
        if (!call.id.equals(Digests.fields(parent.id, call.placement, "root"))
                || !call.childId.equals(child.id) || !child.id.equals(childId(parent, call))
                || !child.key.equals("child:" + call.id) || !call.deliveries.isEmpty()) {
            throw changed("child identity or parent attachment changed");
        }
        RunState root = tx.get(parent.rootId);
        Deadline deadline = deadline(parent, child.admitted, workflow);
        String compatibility = compatibility(workflow, deployment, policy);
        if (!child.rootId.equals(root.id) || child.depth != parent.depth + 1
                || !child.authored.equals(workflow.authoredIdentity())
                || !child.compatibility.equals(compatibility) || !child.deployment.equals(deployment)
                || !child.deployment.equals(parent.deployment)
                || child.admitted < parent.admitted || child.admitted >= parent.deadline
                || child.deadline != deadline.instant() || !child.deadlineOrigin.equals(deadline.origin())
                || child.leaseMillis != parent.leaseMillis || child.maximumDeliveries != parent.maximumDeliveries
                || child.maximumChildDepth != root.maximumChildDepth || child.maximumDescendants != root.maximumDescendants
                || child.depth > root.maximumChildDepth || root.descendants < 1 || root.descendants > root.maximumDescendants) {
            throw changed("accepted child lineage/deadline/compatibility disagrees");
        }
        var rootRecipe = rootRecipe(workflow);
        RunState.Value childInput = child.values.get(valueId(rootRecipe.identity()));
        verifyValue(childInput, rootRecipe);
        RunState.Value parentInput = parent.values.get(call.input);
        if (parentInput == null || !parentInput.id.equals(call.input)
                || !parentInput.type.equals(childInput.type) || !parentInput.shape.equals(childInput.shape)
                || !parentInput.codec.equals(childInput.codec) || !parentInput.digest.equals(Digests.of(parentInput.payload))
                || !child.admission.equals(Digests.fields(compatibility, parentInput.digest))
                || !childInput.producer.isEmpty() || !childInput.sourceRun.equals(parent.id)
                || !childInput.sourceValue.equals(parentInput.id) || !Arrays.equals(childInput.payload, parentInput.payload)) {
            throw changed("accepted child input or source provenance disagrees");
        }
    }

    /** A copied result remains tied to its committed source on subsequent selected-value reads. */
    static void verifyOutput(JdbcRunStore.Tx tx, RunState parent, RunState.Invocation call,
            RunState.Value copied, ValidatedWorkflow childWorkflow, ApplicationDeployment.Manifest deployment,
            ExecutionPolicy policy) throws Exception {
        RunState child = tx.get(call.childId);
        verify(tx, child, childWorkflow, deployment, policy);
        if (!child.status.equals("SUCCEEDED") || !call.status.equals("COMMITTED")
                || !call.childOutcome.equals(child.status) || !call.childReason.equals(child.reasonCode) || call.settledAt == 0) {
            throw changed("successful child settlement disagrees");
        }
        RunState.Value source = child.values.get(child.output);
        verifyValue(source, childWorkflow.values().get(childWorkflow.terminal().successValue()));
        if (!child.parentId.equals(parent.id) || !call.output.equals(copied.id) || !copied.producer.equals(call.id) || !copied.sourceRun.equals(child.id)
                || !copied.sourceValue.equals(source.id) || !Arrays.equals(copied.payload, source.payload)) {
            throw changed("settled child output or source provenance disagrees");
        }
    }

    private static WorkflowRefusal changed(String message) {
        return new WorkflowRefusal("CHILD_FACTS_CHANGED", message);
    }
}
