package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.Workflows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.Request;

class ExecutionFailureTest {

	@TempDir
	Path directory;

	static final class InterruptedWait implements Step<Request, Request> {

		final CountDownLatch entered = new CountDownLatch(1);

		final AtomicReference<Thread> caller = new AtomicReference<>();

		final AtomicReference<InterruptedException> cause = new AtomicReference<>();

		public Request execute(StepContext context, Request input) {
			caller.set(Thread.currentThread());
			entered.countDown();
			try {
				new CountDownLatch(1).await();
				throw new AssertionError("unreachable");
			}
			catch (InterruptedException failure) {
				assertThat(Thread.currentThread().isInterrupted()).isFalse();
				cause.set(failure);
				// Deliberately preserve only the cause: runtime must recognize the
				// cleared interrupt.
				throw new IllegalStateException("wrapped wait", failure);
			}
		}

	}

	@Test
	void interruptedWaitWrapperPersistsFailureBeforeRestoringCallerFlag() throws Exception {
		var step = new InterruptedWait();
		var deployment = new TestApplication("failure", "v1", Map.of(), Map.of("work", step));
		var workflow = Workflows.define("wait").then("work", deployment.step("work")).terminate(Terminal.SUCCEEDED).build();
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility());
				var threads = Executors.newVirtualThreadPerTaskExecutor()) {
			var run = runtime.start(workflow, "one", new Request("input"));
			var task = threads.submit(() -> {
				try {
					RunSnapshot result = runtime.resume(run.runId(), workflow);
					assertThat(Thread.currentThread().isInterrupted()).isTrue();
					return result;
				}
				finally {
					Thread.interrupted();
				}
			});
			try {
				assertThat(step.entered.await(10, TimeUnit.SECONDS)).isTrue();
				step.caller.get().interrupt();
				var after = task.get(10, TimeUnit.SECONDS);
				assertThat(step.cause.get()).isNotNull();
				assertThat(after.status()).isEqualTo(RunSnapshot.Status.FAILED);
				assertThat(after.reason().code()).isEqualTo("STEP_FAILED");
				assertThat(after.currentNode()).isEqualTo(workflow.graph().startNode());
				assertThat(runtime.inspect(run.runId())).isEqualTo(after);
				assertThat(runtime.resume(run.runId(), workflow)).isEqualTo(after);
			}
			finally {
				if (step.caller.get() != null)
					step.caller.get().interrupt();
			}
		}
	}

	static final class Failure implements Step<Request, Request> {

		final RuntimeException failure;

		Failure(RuntimeException failure) {
			this.failure = failure;
		}

		public Request execute(StepContext context, Request input) {
			throw failure;
		}

	}

	@Test
	void applicationFailureCodeAndCheckedIoCauseDoNotBecomeCodecFailures() {
		IOException io = new IOException("disk unavailable");
		var unchecked = new UncheckedIOException("application IO", io);
		for (RuntimeException failure : java.util.List.of(unchecked,
				new WorkflowRefusal("ENCODE_FAILED", "from application"))) {
			var step = new Failure(failure);
			var deployment = new TestApplication("failure", "v1", Map.of(), Map.of("work", step));
			var workflow = Workflows.define("failure").then("work", deployment.step("work")).terminate(Terminal.SUCCEEDED).build();
			try (var runtime = DurableWorkflows.open(directory.resolve(failure.getClass().getSimpleName()), deployment.registry(), deployment.compatibility())) {
				var run = runtime.start(workflow, "one", new Request("input"));
				assertThatThrownBy(() -> step
					.execute(new StepContext("r", "i", "a", 1, java.time.Instant.MAX, Map.of()), new Request("x")))
					.isSameAs(failure);
				var after = runtime.resume(run.runId(), workflow);
				assertThat(after.reason().code()).isEqualTo("STEP_FAILED");
				assertThat(after.events()).noneMatch(event -> event.kind().equals("RESULT_COMMITTED"));
				assertThat(Thread.currentThread().isInterrupted()).isFalse();
			}
		}
		assertThat(unchecked).hasCause(io);
	}

}
