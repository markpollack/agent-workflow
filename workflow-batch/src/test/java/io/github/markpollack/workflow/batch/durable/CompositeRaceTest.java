package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static io.github.markpollack.workflow.batch.durable.CompositeExecutionTest.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;

class CompositeRaceTest {

	@TempDir
	Path directory;

	static final class Block implements Step<Text, Text> {

		final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

		Thread thread;

		public Text execute(StepContext context, Text input) {
			thread = Thread.currentThread();
			entered.countDown();
			try {
				if (!release.await(15, TimeUnit.SECONDS))
					throw new AssertionError("release timeout");
			}
			catch (InterruptedException ex) {
				throw new IllegalStateException(ex);
			}
			return input;
		}

	}

	@Test
	void nestedBlockedStepHoldsNoStoreLockAndCancellationFencesItsLateResult() throws Exception {
		var blocked = new Block();
		var calls = new ArrayList<String>();
		var quick = new Prefix("Q", calls);
		var nested = wrapper(wrapper(Workflows.define("inner").then(blocked).terminate(SUCCEEDED).build()));
		var other = Workflows.define("other").then(quick).terminate(SUCCEEDED).build();
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"),
				StepRegistry.of(Map.of("blocked", blocked, "quick", quick)), compatibility());
				var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			String id = runtime.start(nested, "nested", new Text("x")).runId();
			var result = threads.submit(() -> runtime.resume(id, nested));
			assertThat(blocked.entered.await(10, TimeUnit.SECONDS)).isTrue();
			try {
				var before = runtime.inspect(id);
				assertThatThrownBy(() -> runtime.advance(id, nested)).isInstanceOfSatisfying(WorkflowRefusal.class,
						e -> assertThat(e.code()).isEqualTo("RUN_BUSY"));
				assertThat(runtime.inspect(id).logicalInvocations()).isEqualTo(before.logicalInvocations());
				String otherId = runtime.start(other, "other", new Text("y")).runId();
				assertThat(runtime.resume(otherId, other).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				assertThat(runtime.result(otherId, other)).isEqualTo(new Text("Qy"));
				var cancelled = runtime.cancel(id, "owner", "stop");
				assertThat(cancelled.scopes().stream().filter(s -> !s.parentScope().isEmpty()))
					.allMatch(s -> s.lifecycle().equals("REVOKED"));
				blocked.release.countDown();
				assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(cancelled);
				assertThat(cancelled.values()).hasSize(3);
				assertThat(cancelled.returns()).isEmpty();
			}
			finally {
				blocked.release.countDown();
			}
		}
	}

	@Test
	void nestedResultAndTerminalAreAtomicAndUseSampledTime() throws Exception {
		for (boolean rollback : List.of(false, true)) {
			var calls = new ArrayList<String>();
			var step = new Prefix("F", calls);
			var child = Workflows.define("short")
				.maxDuration(Duration.ofMillis(100))
				.then(step)
				.terminate(SUCCEEDED)
				.build();
			var workflow = wrapper(child);
			var file = directory.resolve("atomic" + rollback);
			AtomicBoolean once = new AtomicBoolean();
			BoundaryHooks fault = (point, id) -> {
				if (point.equals("BEFORE_RESULT_COMMIT") && once.compareAndSet(false, true)) {
					time(10_101);
					if (rollback)
						throw new DurableRaceTest.SimulatedCrash();
				}
			};
			try (var runtime = new DurableWorkflows(file, StepRegistry.of(Map.of("fast", step)), compatibility(),
					ExecutionPolicy.DEFAULT, fault)) {
				controlledClock(file, 10_000);
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				runtime.advance(id, workflow);
				time(10_099);
				if (rollback) {
					assertThatThrownBy(() -> runtime.advance(id, workflow))
						.isInstanceOf(DurableRaceTest.SimulatedCrash.class);
					var raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree(state(file, id));
					var childJson = raw.path("scopes").elements();
					childJson.next();
					var saved = childJson.next();
					assertThat(saved.path("localOutcome").isNull()).isTrue();
					assertThat(raw.path("values").size()).isEqualTo(2);
					assertThat(runtime.resume(id, workflow).reason().code()).isEqualTo("DEADLINE_EXCEEDED");
				}
				else {
					var result = runtime.advance(id, workflow);
					var local = result.scopes()
						.stream()
						.filter(s -> !s.parentScope().isEmpty())
						.findFirst()
						.orElseThrow();
					assertThat(local.localOutcome().reason().time().toEpochMilli()).isEqualTo(10_099);
					assertThat(runtime.resume(id, workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				}
				assertThat(calls).containsExactly("F:x");
			}
		}
	}

	@Test
	void enclosingExpiryPreservesAcceptedInnerSuccessOrFailureAndRevokesReturn() throws Exception {
		for (boolean failure : List.of(false, true)) {
			var step = new MaybeFail(failure);
			var child = Workflows.define("child")
				.maxDuration(Duration.ofMillis(50))
				.then(step)
				.terminate(SUCCEEDED)
				.build();
			var parent = Workflows.define("parent")
				.maxDuration(Duration.ofMillis(100))
				.subWorkflow("inner", child)
				.terminate(SUCCEEDED)
				.build();
			var workflow = wrapper(parent);
			var file = directory.resolve("ancestor" + failure);
			try (var runtime = DurableWorkflows.open(file, StepRegistry.of(Map.of("step", step)), compatibility())) {
				controlledClock(file, 10_000);
				String id = runtime.start(workflow, "one", new Text("x")).runId();
				runtime.advance(id, workflow);
				runtime.advance(id, workflow);
				var accepted = runtime.advance(id, workflow);
				var inner = accepted.scopes().stream().filter(s -> s.depth() == 2).findFirst().orElseThrow();
				assertThat(inner.localOutcome().status()).isEqualTo(failure ? "FAILED" : "SUCCEEDED");
				time(10_100);
				var expired = runtime.inspect(id);
				var retained = expired.scopes().stream().filter(s -> s.depth() == 2).findFirst().orElseThrow();
				assertThat(retained.localOutcome()).isEqualTo(inner.localOutcome());
				assertThat(retained.lifecycle()).isEqualTo("REVOKED");
				var end = runtime.resume(id, workflow);
				assertThat(end.reason().code()).isEqualTo("DEADLINE_EXCEEDED");
				assertThat(end.returns()).hasSize(1);
			}
		}
	}

	static final class MaybeFail implements Step<Text, Text> {

		final boolean fail;

		MaybeFail(boolean fail) {
			this.fail = fail;
		}

		public Text execute(StepContext c, Text input) {
			if (fail)
				throw new IllegalStateException("known failure");
			return input;
		}

	}

}
