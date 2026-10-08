package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.batch.examples.FanOutReviewExample.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;

/**
 * Separate JVM recovery with independent external call counters and changed discovery
 * data.
 */
public final class FanOutProcessWorker {

	static class Discover extends DiscoverFiles {

		final Path directory;

		Discover(Path directory) {
			this.directory = directory;
		}

		public List<FilePatch> execute(StepContext c, ReviewRequest r) {
			ParallelProcessWorker.record(directory, "discover", c.invocationId());
			try {
				return Files.readAllLines(directory.resolve("source.txt"))
					.stream()
					.map(p -> new FilePatch(p, r.revision()))
					.toList();
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

	static class Review extends ReviewFile {

		final Path directory;

		final boolean crash;

		final CountDownLatch entered = new CountDownLatch(1);

		Review(Path directory, boolean crash) {
			this.directory = directory;
			this.crash = crash;
		}

		public FileReview execute(StepContext c, FilePatch input) {
			ParallelProcessWorker.record(directory, input.path(), c.invocationId() + ":" + input.diff());
			if (input.path().equals("second")) {
				entered.countDown();
				if (crash)
					while (true)
						LockSupport.parkNanos(10_000_000);
			}
			if (crash && input.path().equals("first"))
				try {
					if (!entered.await(10, TimeUnit.SECONDS))
						throw new IllegalStateException("second item not entered");
				}
				catch (InterruptedException ex) {
					throw new IllegalStateException(ex);
				}
			return new FileReview(input.path(), false);
		}

	}

	static class ReportStep extends WriteReport {

		final Path directory;

		ReportStep(Path directory) {
			this.directory = directory;
		}

		public Report execute(StepContext c, ReportInput p) {
			ParallelProcessWorker.record(directory, "report", p.toString());
			return super.execute(c, p);
		}

	}

	public static void main(String[] args) throws Exception {
		Path directory = Path.of(args[0]);
		String mode = args[1];
		boolean crash = mode.equals("crash");
		var discover = new Discover(directory);
		var review = new Review(directory, crash);
		var report = new ReportStep(directory);
		var workflow = Workflows.define("recover-files")
			.then(discover)
			.forEach("files")
			.maxItems(3)
			.maxInFlight(2)
			.allSuccessful()
			.then(review)
			.end()
			.then(report)
			.terminate(SUCCEEDED)
			.build();
		var mapper = new ObjectMapper();
		var file = directory.resolve("runs");
		BoundaryHooks hooks = (boundary, id) -> {
			if (crash && boundary.equals("AFTER_RESULT_COMMIT"))
				try {
					var state = mapper.readTree(StoreTestSupport.state(file, id));
					boolean firstDone = false, secondUnresolved = false;
					for (var call : state.path("invocations")) {
						var output = state.path("values").path(call.path("output").asText());
						if (call.path("outcome").asText().equals("COMMITTED")
								&& output.path("type").asText().equals(FileReview.class.getName())
								&& new String(Base64.getDecoder().decode(output.path("payload").asText()))
									.contains("first"))
							firstDone = true;
						if (call.path("status").asText().equals("UNRESOLVED")
								&& call.path("placement").asText().contains("4:body4:item"))
							secondUnresolved = true;
					}
					if (firstDone && secondUnresolved) {
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
		};
		try (var rt = new DurableWorkflows(file,
				StepRegistry.of(Map.of("discover", discover, "review", review, "report", report)),
				new ExecutionCompatibility("fan-process", "1", Map.of()), new ExecutionPolicy(3, 32, 10000, 2),
				hooks)) {
			String id = rt.start(workflow, "r", new ReviewRequest("original-sha")).runId();
			Files.writeString(directory.resolve(mode + "-before.json"), StoreTestSupport.state(file, id));
			var end = rt.resume(id, workflow);
			Files.writeString(directory.resolve(mode + "-after.json"), StoreTestSupport.state(file, id));
			Files.writeString(directory.resolve(mode + "-result.txt"), end.status() + " " + rt.result(id, workflow));
		}
	}

}
