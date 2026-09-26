package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.flows.compiler.DeadlinePolicy;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import java.util.List;

import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;
import static io.github.markpollack.workflow.batch.durable.ChildFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildIntegrityTest {
    @TempDir Path directory;

    static Stream<Arguments> entryMutations() {
        return Stream.of("before-claim", "after-claim", "after-charge").flatMap(stage ->
                Stream.of("parentInvocation", "sourceValue", "parentAttachment", "payload")
                        .map(field -> Arguments.of(stage, field)));
    }

    @ParameterizedTest @MethodSource("entryMutations")
    void discoveredChildChecksAcceptedFactsBeforeEachExecutionBoundary(String stage, String field) throws Exception {
        Path file = directory.resolve("runs");
        var deployment = deployment(Map.of("evidence", directory.toString()));
        var built = repeated(deployment, 1, Echo.class, Terminal.SUCCEEDED, DURATION, DURATION);
        String parent, child;
        try (var runtime = DurableWorkflows.open(file, deployment)) {
            parent = runtime.start(built.parent(), "key", new Request("original")).runId();
            child = childId(accept(runtime, parent, built.parent(), "accept"));
        }
        try (var runtime = new DurableWorkflows(file, deployment, ExecutionPolicy.DEFAULT, (point, id) -> {
            if (stage.equals("after-charge") && point.equals("AFTER_DISPATCH_COMMIT")) {
                try { changeInput(file, parent, child, field); }
                catch (Exception ex) { throw new AssertionError(ex); }
            }
        })) {
            assertThat(runtime.discover()).extracting(RunSnapshot::runId).contains(child);
            if (stage.equals("before-claim")) {
                changeInput(file, parent, child, field);
                String before = StoreTestSupport.state(file, child);
                assertThatThrownBy(() -> runtime.claim(child, built.child(), "child"))
                        .isInstanceOf(WorkflowRefusal.class);
                assertThat(StoreTestSupport.state(file, child)).isEqualTo(before);
            } else {
                var lease = runtime.claim(child, built.child(), "child");
                if (stage.equals("after-claim")) changeInput(file, parent, child, field);
                assertThatThrownBy(() -> runtime.advance(lease, built.child()))
                        .isInstanceOf(WorkflowRefusal.class);
                assertThatThrownBy(() -> runtime.renew(lease)).isInstanceOf(WorkflowRefusal.class);
            }
            var saved = runtime.inspect(child);
            assertThat(saved.invocations()).hasSize(stage.equals("after-charge") ? 1 : 0);
            if (stage.equals("after-charge")) {
                assertThat(saved.invocations().getFirst().deliveries()).hasSize(1);
                assertThat(saved.invocations().getFirst().status()).isEqualTo("UNRESOLVED");
            }
            assertThat(Files.exists(directory.resolve("echo.calls"))).isFalse();
        }
    }

    private static void changeInput(Path file, String parent, String child, String field) throws Exception {
        if (field.equals("parentAttachment")) {
            StoreTestSupport.mutate(file, parent, node ->
                    ((ObjectNode)node.path("invocations").get(0)).put("childId", "foreign-child"));
        } else {
            StoreTestSupport.mutate(file, child, node -> {
                if (field.equals("parentInvocation")) ((ObjectNode)node).put(field, "foreign-call");
                else {
                    var input = (ObjectNode)node.path("values").elements().next();
                    if (field.equals("payload")) {
                        byte[] changed = new io.github.markpollack.workflow.flows.compiler.TypeContracts()
                                .encodeBytes(new Request("changed"), Request.class);
                        input.put("payload", changed);
                        input.put("digest", Digests.of(changed));
                    } else input.put(field, "foreign-value");
                }
            });
        }
    }

    @ParameterizedTest @ValueSource(strings={"sourceRun", "sourceValue", "sourceMissing"})
    void terminalResultChecksTheCommittedChildSource(String field) throws Exception {
        Path file = directory.resolve("runs");
        var deployment = deployment(Map.of("evidence", directory.toString()));
        var built = repeated(deployment, 1, Echo.class, Terminal.SUCCEEDED, DURATION, DURATION);
        String parent, child;
        try (var runtime = DurableWorkflows.open(file, deployment)) {
            parent = runtime.start(built.parent(), "key", new Request("original")).runId();
            var done = runtime.resume(parent, built.parent());
            child = done.invocations().getFirst().childRunId();
            assertThat(runtime.result(parent, built.parent())).isEqualTo(new Request("original"));
        }
        if (field.equals("sourceMissing")) {
            StoreTestSupport.mutate(file, child, node -> ((ObjectNode)node.path("values")).remove(node.path("output").asText()));
        } else {
            StoreTestSupport.mutate(file, parent, node ->
                    ((ObjectNode)node.path("values").get(node.path("output").asText())).put(field, ""));
        }
        try (var runtime = DurableWorkflows.open(file, deployment)) {
            String before = StoreTestSupport.state(file, parent);
            assertThatThrownBy(() -> runtime.result(parent, built.parent())).isInstanceOf(WorkflowRefusal.class);
            assertThat(StoreTestSupport.state(file, parent)).isEqualTo(before);
            assertThat(Files.readAllLines(directory.resolve("echo.calls"))).hasSize(1);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false, true})
    void laterOperationOrChildCannotConsumeAChangedCopiedInput(boolean nextChild) throws Exception {
        Path file = directory.resolve("runs");
        var deployment = deployment(Map.of("evidence", directory.toString()));
        var built = repeated(deployment, 2, Echo.class, Terminal.SUCCEEDED, DURATION, DURATION);
        var workflow = built.parent();
        if (!nextChild) {
            var definition = new Definition<>("consumer", Request.class, Request.class, List.of(
                    new Child("child", built.child().definition()), new Call("after", Op.named("after", Request.class, Request.class)),
                    new End(Terminal.SUCCEEDED, "")), DURATION);
            var childPlacement = new Placement(List.of(new Segment("workflow", "consumer", 0), new Segment("node", "child", 0)));
            var afterPlacement = new Placement(List.of(new Segment("workflow", "consumer", 0), new Segment("node", "after", 1)));
            workflow = ValidatedWorkflow.compileWithChildren(definition,
                    Map.of(afterPlacement, deployment.selection(Echo.class, Request.class, Request.class)),
                    Map.of(childPlacement, built.child()), DeadlinePolicy.DEFAULT);
        }
        final var parentWorkflow = workflow;
        try (var runtime = DurableWorkflows.open(file, deployment)) {
            String parent = runtime.start(parentWorkflow, "key", new Request("original")).runId();
            String child = childId(accept(runtime, parent, parentWorkflow, "accept"));
            runtime.resume(child, built.child());
            var lease = runtime.claim(parent, parentWorkflow, "settle");
            var settled = runtime.advance(lease, parentWorkflow);
            assertThat(settled.nextOperation()).isEqualTo(1);
            String output = settled.invocations().getFirst().outputValue();
            StoreTestSupport.mutate(file, parent, node -> ((ObjectNode)node.path("values").get(output)).put("sourceRun", "foreign-child"));
            String before = StoreTestSupport.state(file, parent);
            assertThatThrownBy(() -> runtime.advance(lease, parentWorkflow)).isInstanceOf(WorkflowRefusal.class);
            assertThat(StoreTestSupport.state(file, parent)).isEqualTo(before);
            assertThat(runtime.inspect(parent).lineage().acceptedDescendants()).isEqualTo(1);
            assertThat(Files.readAllLines(directory.resolve("echo.calls"))).hasSize(1);
        }
    }

    @Test void lineageLimitDoesNotEmitAnAcceptanceCommitBoundary() {
        var deployment = deployment(Map.of());
        var built = repeated(deployment, 1, Echo.class, Terminal.SUCCEEDED, DURATION, DURATION);
        var policy = new ExecutionPolicy(java.time.Duration.ofSeconds(5), 3, 0, 0);
        try (var runtime = new DurableWorkflows(directory.resolve("runs"), deployment, policy, (point, id) -> {
            if (point.equals("AFTER_CHILD_ACCEPT_COMMIT")) throw new AssertionError("no child was accepted");
        })) {
            String id = runtime.start(built.parent(), "key", new Request("original")).runId();
            assertThat(runtime.resume(id, built.parent()).reason().code()).isEqualTo("CHILD_LIMIT_EXCEEDED");
            assertThat(runtime.inspect(id).lineage().acceptedDescendants()).isZero();
        }
    }
}
