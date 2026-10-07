package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.ParallelExecutionTest.*;

class ParallelLifecycleTest {

	@TempDir
	Path directory;

	public static class Blocking extends Echo {

		final CountDownLatch entered, release;

		Blocking(CountDownLatch entered, CountDownLatch release) {
			super("block");
			this.entered = entered;
			this.release = release;
		}

		public Text execute(StepContext c, Text t) {
			entered.countDown();
			await(release);
			return super.execute(c, t);
		}

	}

	static ValidatedWorkflow definition(Echo first, Echo second, Aggregate aggregate) {
		return Workflows.define("lifecycle")
			.maxDuration(Duration.ofSeconds(20))
			.parallel("checks")
			.allSuccessful()
			.branch("first")
			.then(first)
			.branch("second")
			.then(second)
			.end()
			.then(aggregate)
			.terminate(SUCCEEDED)
			.build();
	}

	@Test
	void cancellationAndDeadlineSealEveryUnresolvedMemberAndFenceLateResults() throws Exception {
		for (boolean deadline : List.of(false, true)) {
			var entered = new CountDownLatch(2);
			var release = new CountDownLatch(1);
			var first = new Blocking(entered, release);
			var second = new Blocking(entered, release);
			var aggregate = new Aggregate();
			var workflow = definition(first, second, aggregate);
			var file = directory.resolve("cancel-" + deadline);
			try (var runtime = DurableWorkflows.open(file,
					StepRegistry.of(Map.of("first", first, "second", second, "aggregate", aggregate)), deployment(),
					new ExecutionPolicy(3, 32, 10000, 2)); var callers = Executors.newSingleThreadExecutor()) {
				var run = runtime.start(workflow, "cancel", new Text("x"));
				var future = callers.submit(() -> runtime.resume(run.runId(), workflow));
				RunSnapshot cancelled;
				try {
					assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
					if (deadline) {
						StoreTestSupport.controlledClock(file, run.deadline().toEpochMilli());
						cancelled = runtime.inspect(run.runId());
					}
					else
						cancelled = runtime.cancel(run.runId(), "test", "cancel pending assessments");
					assertThat(cancelled.groups()).singleElement().satisfies(g -> {
						assertThat(g.phase()).isEqualTo("REVOKED");
						assertThat(g.members()).hasSize(2)
							.allMatch(m -> m.code().equals(deadline ? "DEADLINE_EXCEEDED" : "CANCELLED"));
					});
				}
				finally {
					release.countDown();
				}
				assertThat(future.get(20, TimeUnit.SECONDS).status())
					.isEqualTo(deadline ? RunSnapshot.Status.FAILED : RunSnapshot.Status.CANCELLED);
				var after = runtime.inspect(run.runId());
				assertThat(after.invocations()).hasSize(2).allMatch(i -> i.status().equals("REVOKED"));
				assertThat(after.values()).hasSize(1);
			}
		}
	}

	@Test
	void shutdownDrainsExistingWorkersAndRefusesNewCallsWithoutLosingOwner() throws Exception {
		var entered = new CountDownLatch(2);
		var release = new CountDownLatch(1);
		var first = new Blocking(entered, release);
		var second = new Blocking(entered, release);
		var aggregate = new Aggregate();
		var workflow = definition(first, second, aggregate);
		var runtime = DurableWorkflows.open(directory.resolve("shutdown"),
				StepRegistry.of(Map.of("first", first, "second", second, "aggregate", aggregate)), deployment(),
				new ExecutionPolicy(3, 32, 10000, 2));
		try (var callers = Executors.newSingleThreadExecutor()) {
			var run = runtime.start(workflow, "shutdown", new Text("x"));
			var future = callers.submit(() -> runtime.resume(run.runId(), workflow));
			try {
				assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(runtime.shutdown(Duration.ZERO)).isFalse();
				assertThatThrownBy(() -> runtime.inspect(run.runId())).isInstanceOf(WorkflowRefusal.class)
					.hasMessageContaining("closing");
				assertThatThrownBy(() -> DurableWorkflows.open(directory.resolve("shutdown")))
					.isInstanceOf(WorkflowRefusal.class);
			}
			finally {
				release.countDown();
			}
			assertThat(future.get(20, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
		}
		finally {
			assertThat(runtime.shutdown(Duration.ofSeconds(5))).isTrue();
		}
	}

	public static class Interrupt extends Echo {

		final boolean fail;

		Interrupt(boolean fail) {
			super("interrupt");
			this.fail = fail;
		}

		public Text execute(StepContext c, Text t) {
			if (fail)
				throw new IllegalStateException(new InterruptedException("leaf interrupted"));
			Thread.currentThread().interrupt();
			return super.execute(c, t);
		}

	}

	@Test
	void workerInterruptionStopsNewWorkRestoresCallerFlagAndAllowsCompatibleRecovery() {
		for (boolean fail : List.of(false, true)) {
			var first = new Interrupt(fail);
			var second = new Echo("second");
			var aggregate = new Aggregate();
			var workflow = definition(first, second, aggregate);
			try (var runtime = DurableWorkflows.open(directory.resolve("interrupt-" + fail),
					StepRegistry.of(Map.of("first", first, "second", second, "aggregate", aggregate)), deployment(),
					new ExecutionPolicy(3, 32, 10000, 1))) {
				var run = runtime.start(workflow, "interrupt", new Text("x"));
				RunSnapshot stopped;
				try {
					stopped = runtime.resume(run.runId(), workflow);
					assertThat(Thread.currentThread().isInterrupted()).isTrue();
				}
				finally {
					Thread.interrupted();
				}
				assertThat(stopped.status()).isEqualTo(RunSnapshot.Status.ACTIVE);
				assertThat(second.calls).isZero();
				assertThat(stopped.invocations()).hasSize(1);
				assertThat(runtime.resume(run.runId(), workflow).status())
					.isEqualTo(fail ? RunSnapshot.Status.FAILED : RunSnapshot.Status.SUCCEEDED);
				assertThat(first.calls).isEqualTo(fail ? 0 : 1);
				assertThat(second.calls).isEqualTo(1);
			}
		}
	}

	public static class RuntimeCalls extends Echo {

		DurableWorkflows runtime;

		ValidatedWorkflow nested;

		String nestedRun;

		String closeCode, resumeCode;

		RuntimeCalls() {
			super("safe");
		}

		public Text execute(StepContext c, Text t) {
			try {
				runtime.close();
			}
			catch (WorkflowRefusal ex) {
				closeCode = ex.code();
			}
			try {
				runtime.resume(nestedRun, nested);
			}
			catch (WorkflowRefusal ex) {
				resumeCode = ex.code();
			}
			return super.execute(c, t);
		}

	}

	@Test
	void workerSelfCloseAndRecursiveExecutionRefuseBeforeCapacityDeadlock() {
		var first = new RuntimeCalls();
		var second = new Echo("second");
		var aggregate = new Aggregate();
		var workflow = definition(first, second, aggregate);
		var nested = Workflows.define("nested-call").then(second).terminate(SUCCEEDED).build();
		try (var runtime = DurableWorkflows.open(directory.resolve("reentrant"),
				StepRegistry.of(Map.of("first", first, "second", second, "aggregate", aggregate)), deployment(),
				new ExecutionPolicy(3, 32, 10000, 1))) {
			first.runtime = runtime;
			first.nested = nested;
			first.nestedRun = runtime.start(nested, "nested-call", new Text("inner")).runId();
			var run = runtime.start(workflow, "outer", new Text("x"));
			assertThat(runtime.resume(run.runId(), workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(first.closeCode).isEqualTo("CLOSE_FROM_EXECUTION");
			assertThat(first.resumeCode).isEqualTo("NESTED_EXECUTION");
			assertThat(runtime.inspect(first.nestedRun).invocations()).isEmpty();
		}
	}

}
