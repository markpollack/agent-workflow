package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.Workflows;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;

class LocalOwnershipTest {

	@TempDir
	Path directory;

	@Test
	void aliasesRefuseWhileOwnedAndReopenAfterCleanClose() throws Exception {
		Path real = Files.createDirectory(directory.resolve("real"));
		Path alias = Files.createSymbolicLink(directory.resolve("alias"), real);
		Path database = real.resolve("runs");
		try (var runtime = DurableWorkflows.open(database)) {
			Files.createSymbolicLink(directory.resolve("linked.mv.db"), real.resolve("runs.mv.db"));
			for (Path other : List.of(database, alias.resolve("runs"), directory.resolve("linked")))
				assertThatThrownBy(() -> DurableWorkflows.open(other)).isInstanceOfSatisfying(WorkflowRefusal.class,
						ex -> assertThat(ex.code()).isEqualTo("OWNER_ACTIVE"));
			assertThat(runtime.discover()).isEmpty();
		}
		try (var reopened = DurableWorkflows.open(alias.resolve("runs"))) {
			assertThat(reopened.discover()).isEmpty();
		}
	}

	@Test
	void resolvedPathCannotInjectJdbcOptionsOrCreateADifferentDatabase() throws Exception {
		Path special = Files.createDirectory(directory.resolve("real;IGNORE_UNKNOWN_SETTINGS=TRUE;X="));
		Path alias = Files.createSymbolicLink(directory.resolve("alias"), special);
		Path fakeData = Files.writeString(special.resolve("other.mv.db"), "not a database");
		Files.createSymbolicLink(directory.resolve("linked.mv.db"), fakeData);
		for (Path file : List.of(alias.resolve("runs"), directory.resolve("linked"), special.resolve("direct")))
			assertThatThrownBy(() -> DurableWorkflows.open(file)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("semicolon");
		assertThat(Files.exists(directory.resolve("real.mv.db"))).isFalse();
		assertThat(Files.readString(fakeData)).isEqualTo("not a database");
	}

	@Test
	void failedStartupReleasesOwnershipAndEmptyOldStoreIsNotInitialized() throws Exception {
		Path database = directory.resolve("runs");
		try (var c = connect(database); var s = c.createStatement()) {
			s.execute("CREATE TABLE aw_run (state CLOB)");
		}
		for (int i = 0; i < 2; i++)
			assertThatThrownBy(() -> DurableWorkflows.open(database)).isInstanceOfSatisfying(WorkflowRefusal.class,
					ex -> assertThat(ex.code()).isEqualTo("STORE_FORMAT"));
		try (var c = connect(database);
				var s = c.createStatement();
				var rows = s
					.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='PUBLIC'")) {
			assertThat(rows.next()).isTrue();
			assertThat(rows.getString(1)).isEqualTo("AW_RUN");
			assertThat(rows.next()).isFalse();
		}
	}

	static final class BlockingStep implements Step<Request, Request> {

		final CountDownLatch entered, release;

		final Set<Thread> callers = ConcurrentHashMap.newKeySet();

		BlockingStep(int count, CountDownLatch release) {
			this.entered = new CountDownLatch(count);
			this.release = release;
		}

		public Request execute(StepContext context, Request input) {
			callers.add(Thread.currentThread());
			entered.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS))
					throw new AssertionError("application release timeout");
			}
			catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("application wait interrupted", failure);
			}
			return input;
		}

	}

	@Test
	void distinctRunsOverlapOnCallingThreadsButSameRunCannotDoubleCharge() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		var step = new BlockingStep(2, release);
		var deployment = new TestApplication("app", "v1", Map.of(), Map.of("blocking", step));
		var workflow = Workflows.define("concurrent").then("blocking", deployment.step("blocking")).terminate(Terminal.SUCCEEDED).build();
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility(), new ExecutionPolicy(1));
				var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			var one = runtime.start(workflow, "one", new Request("one"));
			var two = runtime.start(workflow, "two", new Request("two"));
			Set<Thread> callers = ConcurrentHashMap.newKeySet();
			var first = threads.submit(() -> {
				callers.add(Thread.currentThread());
				return runtime.resume(one.runId(), workflow);
			});
			var second = threads.submit(() -> {
				callers.add(Thread.currentThread());
				return runtime.advance(two.runId(), workflow);
			});
			try {
				assertThat(step.entered.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(step.callers).containsExactlyInAnyOrderElementsOf(callers);
				assertThatThrownBy(() -> runtime.resume(one.runId(), workflow))
					.isInstanceOfSatisfying(WorkflowRefusal.class, ex -> assertThat(ex.code()).isEqualTo("RUN_BUSY"));
				assertThat(runtime.inspect(one.runId()).invocations().getFirst().attempts()).hasSize(1);
			}
			finally {
				release.countDown();
			}
			assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
		}
	}

	@Test
	void anotherRunCommitsWhileOneStepRemainsBlocked() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		var blocked = new BlockingStep(1, release);
		var echo = new Echo(new OperationEvidence(null), "!");
		var registry = StepRegistry.of(Map.of("blocked", blocked, "echo", echo));
		var compatibility = new ExecutionCompatibility("app", "v1", Map.of("suffix", "!"));
		var waiting = Workflows.define("blocked").then(blocked).terminate(Terminal.SUCCEEDED).build();
		var quick = Workflows.define("quick").then(echo).terminate(Terminal.SUCCEEDED).build();
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), registry, compatibility);
				var callers = Executors.newVirtualThreadPerTaskExecutor()) {
			var one = runtime.start(waiting, "one", new Request("one"));
			var first = callers.submit(() -> runtime.resume(one.runId(), waiting));
			try {
				assertThat(blocked.entered.await(10, TimeUnit.SECONDS)).isTrue();
				var second = callers.submit(() -> {
					var two = runtime.start(quick, "two", new Request("two"));
					return runtime.resume(two.runId(), quick);
				});
				var completed = second.get(5, TimeUnit.SECONDS);
				assertThat(completed.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				assertThat(runtime.inspect(completed.runId())).isEqualTo(completed);
				assertThat(runtime.result(completed.runId(), quick)).isEqualTo(new Request("two!"));
				assertThat(first.isDone()).isFalse();
				assertThat(release.getCount()).isEqualTo(1);
				assertThat(runtime.inspect(one.runId()).status()).isEqualTo(RunSnapshot.Status.ACTIVE);
			}
			finally {
				release.countDown();
			}
			assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
		}
	}

	@Test
	void shutdownRetainsOwnershipUntilApplicationReturnsThenConcurrentClosersDrain() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		var step = new BlockingStep(1, release);
		var deployment = new TestApplication("app", "v1", Map.of(), Map.of("blocking", step));
		var workflow = Workflows.define("shutdown").then("blocking", deployment.step("blocking")).terminate(Terminal.SUCCEEDED).build();
		Path database = directory.resolve("runs");
		var runtime = DurableWorkflows.open(database, deployment.registry(), deployment.compatibility());
		try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			var run = runtime.start(workflow, "one", new Request("x"));
			var caller = threads.submit(() -> runtime.resume(run.runId(), workflow));
			try {
				assertThat(step.entered.await(10, TimeUnit.SECONDS)).isTrue();
				assertThat(runtime.shutdown(Duration.ZERO)).isFalse();
				assertThatThrownBy(runtime::discover).isInstanceOfSatisfying(WorkflowRefusal.class,
						ex -> assertThat(ex.code()).isEqualTo("RUNTIME_CLOSED"));
				assertThatThrownBy(() -> DurableWorkflows.open(database)).isInstanceOfSatisfying(WorkflowRefusal.class,
						ex -> assertThat(ex.code()).isEqualTo("OWNER_ACTIVE"));
				assertThat(caller.isDone()).isFalse();
				var first = threads.submit(() -> runtime.shutdown(Duration.ofSeconds(10)));
				var second = threads.submit(() -> runtime.shutdown(Duration.ofSeconds(10)));
				release.countDown();
				assertThat(caller.get(10, TimeUnit.SECONDS).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
				assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
			}
			finally {
				release.countDown();
				runtime.close();
			}
			try (var next = DurableWorkflows.open(database, deployment.registry(), deployment.compatibility())) {
				assertThat(next.result(run.runId(), workflow)).isEqualTo(new Request("x"));
			}
		}
	}

	static final class SelfClosing implements Step<Request, Request> {

		DurableWorkflows runtime;

		public Request execute(StepContext context, Request input) {
			assertThatThrownBy(runtime::close).isInstanceOfSatisfying(WorkflowRefusal.class,
					ex -> assertThat(ex.code()).isEqualTo("CLOSE_FROM_EXECUTION"));
			return input;
		}

	}

	@Test
	void closeFromApplicationCallRefusesWithoutClosingRuntime() {
		var step = new SelfClosing();
		var deployment = new TestApplication("app", "v1", Map.of(), Map.of("work", step));
		var workflow = Workflows.define("self-close").then("work", deployment.step("work")).terminate(Terminal.SUCCEEDED).build();
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility())) {
			step.runtime = runtime;
			var run = runtime.start(workflow, "one", new Request("x"));
			assertThat(runtime.resume(run.runId(), workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
		}
	}

	static final class Interrupting implements Step<Request, Request> {

		final boolean throwsException;

		Interrupting(boolean throwsException) {
			this.throwsException = throwsException;
		}

		public Request execute(StepContext context, Request input) {
			Thread.currentThread().interrupt();
			if (throwsException)
				throw new IllegalStateException("step stopped", new InterruptedException("step stopped"));
			return input;
		}

	}

	@Test
	void applicationInterruptPersistsOutcomeRestoresFlagAndDoesNotEnterSuccessor() throws Exception {
		for (boolean throwsException : List.of(false, true)) {
			AtomicInteger effects = new AtomicInteger();
			Step<Request, Request> successor = new Step<>() {
				public Request execute(StepContext context, Request input) {
					effects.incrementAndGet();
					return input;
				}
			};
			var deployment = new TestApplication("app", "v1", Map.of(),
					Map.of("interrupt", new Interrupting(throwsException), "successor", successor));
			var workflow = Workflows.define("interrupt")
				.then("interrupt", deployment.step("interrupt"))
				.then("successor", deployment.step("successor"))
				.terminate(Terminal.SUCCEEDED)
				.build();
			try (var runtime = DurableWorkflows.open(directory.resolve("interrupt-" + throwsException), deployment.registry(), deployment.compatibility())) {
				var run = runtime.start(workflow, "one", new Request("x"));
				RunSnapshot after;
				try {
					after = runtime.resume(run.runId(), workflow);
					assertThat(Thread.currentThread().isInterrupted()).isTrue();
					assertThatThrownBy(runtime::discover).isInstanceOfSatisfying(WorkflowRefusal.class,
							ex -> assertThat(ex.code()).isEqualTo("INTERRUPTED"));
				}
				finally {
					Thread.interrupted();
				}
				assertThat(effects.get()).isZero();
				assertThat(after.status())
					.isEqualTo(throwsException ? RunSnapshot.Status.FAILED : RunSnapshot.Status.ACTIVE);
				assertThat(after.invocations().getFirst().attempts()).hasSize(1);
				if (!throwsException) {
					assertThat(after.currentNode()).isEqualTo(workflow.graph().unconditionalSuccessor(workflow.graph().startNode()));
					runtime.resume(run.runId(), workflow);
					assertThat(effects.get()).isEqualTo(1);
				}
				else
					assertThat(runtime.resume(run.runId(), workflow)).isEqualTo(after);
			}
		}
	}

	@Test
	void malformedSavedInvocationRefusesBeforeAnotherAttemptOrEvidenceMutation() throws Exception {
		for (String field : List.of("id", "placement", "input", "output", "status")) {
			Path file = directory.resolve(field);
			var deployment = deployment(Map.of());
			var workflow = echo(deployment, Echo.class, null);
			BoundaryHooks crash = (point, run) -> {
				if (point.equals("AFTER_DISPATCH_COMMIT"))
					throw new DurableRaceTest.SimulatedCrash();
			};
			try (var runtime = new DurableWorkflows(file, deployment.registry(), deployment.compatibility(), ExecutionPolicy.DEFAULT, crash)) {
				var run = runtime.start(workflow, "one", new Request("x"));
				assertThatThrownBy(() -> runtime.advance(run.runId(), workflow))
					.isInstanceOf(DurableRaceTest.SimulatedCrash.class);
				mutate(file, run.runId(),
						state -> ((com.fasterxml.jackson.databind.node.ObjectNode) state.path("invocations").get(0))
							.put(field, "changed"));
				String before = state(file, run.runId());
				assertThatThrownBy(() -> runtime.advance(run.runId(), workflow)).isInstanceOfSatisfying(
						WorkflowRefusal.class, ex -> assertThat(ex.code()).isEqualTo(field.equals("input") ? "VALUE_MISSING" : "PROGRESS_INVALID"));
				assertThat(state(file, run.runId())).isEqualTo(before);
			}
		}
	}

}
