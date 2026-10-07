package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.batch.examples.ParallelAssessmentExample;
import io.github.markpollack.workflow.batch.examples.ParallelAssessmentExample.*;

/**
 * Actual process death while one member is committed and another external call
 * unresolved.
 */
public final class ParallelProcessWorker {

	static void record(Path directory, String name, String text) {
		try {
			Files.writeString(directory.resolve(name + ".calls"), text + "\n", StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static final class Quality extends AssessQuality {

		final Path directory;

		final CountDownLatch backportEntered;

		Quality(Path directory, CountDownLatch backportEntered) {
			this.backportEntered = backportEntered;
			this.directory = directory;
		}

		public QualityAssessment execute(StepContext c, Revision input) {
			record(directory, "quality", c.invocationId() + ":" + input.commit());
			try {
				if (!backportEntered.await(10, TimeUnit.SECONDS))
					throw new IllegalStateException("backport entry timeout");
			}
			catch (InterruptedException ex) {
				throw new IllegalStateException(ex);
			}
			return super.execute(c, input);
		}

	}

	public static final class Backport extends AssessBackport {

		final Path directory;

		final boolean crash;

		final CountDownLatch entered;

		Backport(Path directory, boolean crash, CountDownLatch entered) {
			this.entered = entered;
			this.directory = directory;
			this.crash = crash;
		}

		public BackportAssessment execute(StepContext c, BackportInput input) {
			record(directory, "backport",
					c.invocationId() + ":" + input.request().number() + ":" + input.revision().commit());
			entered.countDown();
			if (crash)
				while (true)
					LockSupport.parkNanos(10_000_000);
			return super.execute(c, input);
		}

	}

	public static final class ReportStep extends WriteReport {

		final Path directory;

		ReportStep(Path directory) {
			this.directory = directory;
		}

		public Report execute(StepContext c, ReportInput input) {
			record(directory, "report", input.toString());
			return super.execute(c, input);
		}

	}

	public static void main(String[] args) throws Exception {
		var directory = Path.of(args[0]);
		String mode = args[1];
		boolean crash = mode.equals("crash");
		var fetch = new FetchRevision();
		var backportEntryLatch = new CountDownLatch(1);
		var quality = new Quality(directory, backportEntryLatch);
		var backport = new Backport(directory, crash, backportEntryLatch);
		var report = new ReportStep(directory);
		var workflow = ParallelAssessmentExample.workflow(fetch, quality, backport, report);
		var mapper = new ObjectMapper();
		var file = directory.resolve("runs");
		BoundaryHooks hooks = (boundary, id) -> {
			if (crash && boundary.equals("AFTER_RESULT_COMMIT")) {
				try {
					var state = mapper.readTree(StoreTestSupport.state(file, id));
					boolean qualityDone = false, backportEntered = false;
					for (var call : state.path("invocations")) {
						if (call.path("placement").asText().contains("quality")
								&& call.path("outcome").asText().equals("COMMITTED"))
							qualityDone = true;
						if (call.path("placement").asText().contains("backport")
								&& call.path("status").asText().equals("UNRESOLVED"))
							backportEntered = true;
					}
					if (qualityDone && backportEntered) {
						Files.writeString(directory.resolve("crash-state.json"), state.toPrettyString());
						Files.writeString(directory.resolve("kill-point.tmp"),
								mapper.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(), "run", id)));
						Files.move(directory.resolve("kill-point.tmp"), directory.resolve("kill-point.json"),
								StandardCopyOption.ATOMIC_MOVE);
						while (true)
							LockSupport.parkNanos(10_000_000);
					}
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
		};
		try (var runtime = new DurableWorkflows(file,
				StepRegistry.of(Map.of("fetch", fetch, "quality", quality, "backport", backport, "report", report)),
				new ExecutionCompatibility("parallel-recovery", "1", Map.of()), new ExecutionPolicy(3, 32, 10000, 2),
				hooks)) {
			String id = runtime.start(workflow, "42", new PullRequest("42")).runId();
			Files.writeString(directory.resolve(mode + "-before.json"), StoreTestSupport.state(file, id));
			var result = runtime.resume(id, workflow);
			Files.writeString(directory.resolve(mode + "-after.json"), StoreTestSupport.state(file, id));
			Files.writeString(directory.resolve(mode + "-result.txt"),
					result.status() + " " + runtime.result(id, workflow));
		}
	}

}
