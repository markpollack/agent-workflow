package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;

class UntimedWorkflowTest {
    @TempDir Path directory;
    public record Text(String value) {}

    static final class Append implements Step<Text, Text> {
        final AtomicInteger calls = new AtomicInteger();
        StepContext last;
        public Text execute(StepContext context, Text input) {
            calls.incrementAndGet(); last = context;
            return new Text(input.value() + "!");
        }
    }

    static final class Block implements Step<Text, Text> {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        public Text execute(StepContext context, Text input) {
            assertThat(context.hasDeadline()).isFalse(); entered.countDown();
            try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            return input;
        }
    }

    static ExecutionCompatibility compatibility() {
        return new ExecutionCompatibility("untimed-test", "v1", Map.of());
    }

    static ValidatedWorkflow untimed(Append step) {
        return Workflows.define("untimed").withoutDeadline().then("first", step).then("second", step)
                .terminate(SUCCEEDED).build();
    }

    @Test void explicitIntentIsSetOnceAndFinitePolicyStillCapsPositiveDurations() {
        var step = new Append();
        var defaults = Workflows.define("default").then(step).terminate(SUCCEEDED).build();
        assertThat(defaults.definition().deadline()).isEqualTo(Duration.ofHours(1));
        assertThat(defaults.deadlineOrigin()).isEqualTo("DEFAULT:local-v1");
        var capped = Workflows.define("capped").maxDuration(Duration.ofHours(2)).then(step).terminate(SUCCEEDED).build();
        assertThat(capped.definition().deadline()).isEqualTo(Duration.ofHours(1));
        assertThat(capped.deadlineOrigin()).isEqualTo("POLICY_CAP:local-v1");
        assertThat(untimed(step).definition().deadline()).isEqualTo(Duration.ZERO);
        assertThatThrownBy(() -> DeadlinePolicy.DEFAULT.resolve(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeadlinePolicy.UNTIMED.resolve(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        var raw = new io.github.markpollack.workflow.flows.compiler.WorkflowModel.Definition<>("raw", Text.class, Text.class,
                List.of(new io.github.markpollack.workflow.flows.compiler.WorkflowModel.End(SUCCEEDED, "")), Duration.ZERO);
        assertThatThrownBy(() -> ValidatedWorkflow.compile(raw, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(DeadlinePolicy.UNTIMED.resolve(Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(2));
        assertThatThrownBy(() -> Workflows.define("bad").maxDuration(Duration.ZERO)).hasMessageContaining("withoutDeadline");
        assertThatThrownBy(() -> Workflows.define("bad").withoutDeadline().maxDuration(Duration.ofMinutes(1)))
                .hasMessageContaining("already configured");
        assertThatThrownBy(() -> Workflows.define("bad").maxDuration(Duration.ofMinutes(1)).withoutDeadline())
                .hasMessageContaining("already configured");
        assertThatThrownBy(() -> Workflows.define("bad").then(step).withoutDeadline().withoutDeadline())
                .hasMessageContaining("already configured");
    }

    @Test void reopenAfterDefaultCeilingReusesCommittedItemsAndSavedUntimedMode() throws Exception {
        Path file = directory.resolve("runs"); var step = new Append();
        var registry = StepRegistry.of(Map.of("append", step)); var workflow = untimed(step);
        RunSnapshot saved;
        try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
            controlledClock(file, 1_000_000);
            var run = runtime.start(workflow, "recover", new Text("a"));
            assertThat(run.hasDeadline()).isFalse();
            assertThat(run.deadline()).isEqualTo(StepContext.NO_DEADLINE);
            assertThat(run.deadlineOrigin()).isEqualTo("UNTIMED:local-v1");
            saved = runtime.advance(run.runId(), workflow);
            assertThat(step.calls).hasValue(1);
            assertThat(step.last.hasDeadline()).isFalse();
        }
        time(1_000_000 + Duration.ofDays(10).toMillis());
        var fresh = new Append(); var recovered = untimed(fresh);
        try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("append", fresh)), compatibility())) {
            assertThat(runtime.discover()).extracting(RunSnapshot::runId).containsExactly(saved.runId());
            assertThat(runtime.inspect(saved.runId())).isEqualTo(saved);
            var completed = runtime.resume(saved.runId(), recovered);
            assertThat(completed.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(completed.invocations().getFirst()).isEqualTo(saved.invocations().getFirst());
            assertThat(fresh.calls).hasValue(1);
            assertThat(runtime.result(saved.runId(), recovered)).isEqualTo(new Text("a!!"));
            assertThat(completed.scopes()).allMatch(scope -> !scope.hasDeadline());
        }
    }

    @Test void finiteAndUntimedChildBoundsInheritWithoutResettingOnReopen() throws Exception {
        for (boolean timedParent : List.of(false, true)) {
            Path file = directory.resolve("nested-" + timedParent); var step = new Append();
            var registry = StepRegistry.of(Map.of("append", step));
            var childStart = Workflows.define("child");
            var child = (timedParent ? childStart.withoutDeadline() : childStart.maxDuration(Duration.ofSeconds(5)))
                    .then(step).terminate(SUCCEEDED).build();
            var parentStart = Workflows.define("parent");
            var parent = (timedParent ? parentStart.maxDuration(Duration.ofSeconds(2)) : parentStart.withoutDeadline())
                    .subWorkflow("child", child).terminate(SUCCEEDED).build();
            RunSnapshot admitted, entered;
            try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
                controlledClock(file, 100_000);
                admitted = runtime.start(parent, "nested", new Text("a"));
                entered = runtime.advance(admitted.runId(), parent);
                var scope = entered.scopes().stream().filter(s -> !s.parentScope().isEmpty()).findFirst().orElseThrow();
                assertThat(scope.hasDeadline()).isTrue();
                assertThat(scope.deadline().toEpochMilli()).isEqualTo(100_000 + (timedParent ? 2_000 : 5_000));
            }
            time(101_000);
            try (var runtime = DurableWorkflows.open(file, registry, compatibility())) {
                assertThat(runtime.inspect(admitted.runId())).isEqualTo(entered);
                var result = runtime.resume(admitted.runId(), parent);
                assertThat(result.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
                assertThat(step.last.hasDeadline()).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nestedGroupMakesCapacityOneProgressWithAndWithoutDeadline(boolean untimed) throws Exception {
        Path file = directory.resolve("groups"); var step = new Append();
        var childStart = Workflows.define("child");
        var child = (untimed ? childStart.withoutDeadline() : childStart).then(step).terminate(SUCCEEDED).build();
        var parentStart = Workflows.define("group");
        var workflow = (untimed ? parentStart.withoutDeadline() : parentStart)
                .parallel("checks").allSuccessful().branch("only").subWorkflow("nested", child).end()
                .then(step).terminate(SUCCEEDED).build();
        try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("append", step)), compatibility(),
                new ExecutionPolicy(2, 32, 10000, 1))) {
            controlledClock(file, 100_000);
            var run = runtime.start(workflow, "group", new Text("a"));
            time(100_000 + (untimed ? Duration.ofDays(10).toMillis() : 1_000));
            var end = runtime.resume(run.runId(), workflow);
            assertThat(end.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(end.scopes()).allMatch(s -> s.hasDeadline() != untimed);
            // The single-member group's structural product does not replace the root Text.
            assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Text("a!"));
            assertThat(step.calls).hasValue(2);
            assertThat(end.invocations().stream().filter(i -> i.kind().equals("LEAF")))
                    .hasSize(2).allMatch(i -> i.status().equals("SETTLED"));
        }
    }

    @Test void cancellationFencesLateResultAndShutdownKeepsItsFiniteBudget() throws Exception {
        Path file = directory.resolve("cancel"); var block = new Block();
        var workflow = Workflows.define("cancel").withoutDeadline().then(block).terminate(SUCCEEDED).build();
        var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("block", block)), compatibility());
        var caller = Executors.newSingleThreadExecutor();
        try {
            var run = runtime.start(workflow, "cancel", new Text("a"));
            var future = caller.submit(() -> runtime.resume(run.runId(), workflow));
            assertThat(block.entered.await(5, TimeUnit.SECONDS)).isTrue();
            var cancelled = runtime.cancel(run.runId(), "operator", "manual stop");
            assertThat(cancelled.status()).isEqualTo(RunSnapshot.Status.CANCELLED);
            assertThat(cancelled.events()).noneMatch(e -> e.kind().equals("RESULT_COMMITTED"));
            assertThat(runtime.shutdown(Duration.ofMillis(20))).isFalse();
            block.release.countDown();
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(cancelled);
            assertThat(runtime.shutdown(Duration.ofSeconds(5))).isTrue();
        } finally {
            block.release.countDown(); caller.shutdown();
            assertThat(caller.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            runtime.close();
        }
        try (var reopened = DurableWorkflows.open(file, StepRegistry.of(Map.of("block", block)), compatibility())) {
            assertThat(reopened.discover()).isEmpty();
        }
    }
}
