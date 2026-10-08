package example;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.RunSnapshot;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;

/** Typed deterministic runtime discovery, per-file review and an ordered report. */
public final class FanOutReviewExample {

	private FanOutReviewExample() {
	}

	public record ReviewRequest(String revision) {
	}

	public record FilePatch(String path, String diff) {
	}

	public record FileReview(String path, boolean acceptable) {
	}

	public record ReportInput(ReviewRequest request, List<FileReview> reviews) {
	}

	public record Report(String revision, List<FileReview> reviews) {
	}

	public static class DiscoverFiles implements Step<ReviewRequest, List<FilePatch>> {

		public List<FilePatch> execute(StepContext context, ReviewRequest request) {
			return List.of(new FilePatch("A.java", request.revision()), new FilePatch("B.java", "TODO"));
		}

	}

	public static class ReviewFile implements Step<FilePatch, FileReview> {

		public FileReview execute(StepContext context, FilePatch item) {
			return new FileReview(item.path(), !item.diff().contains("TODO"));
		}

	}

	public static class WriteReport implements Step<ReportInput, Report> {

		public Report execute(StepContext context, ReportInput input) {
			return new Report(input.request().revision(), input.reviews());
		}

	}

	public static ValidatedWorkflow workflow(DiscoverFiles discoverFiles, ReviewFile reviewFile,
			WriteReport writeReport) {
		return Workflows.define("review-changed-files")
			.then(discoverFiles)
			.forEach("files")
			.maxItems(40)
			.maxInFlight(4)
			.allSuccessful()
			.then(reviewFile)
			.end()
			.then(writeReport)
			.terminate(SUCCEEDED)
			.build();
	}

	/** Run on a new directory, or repeat on the same directory to reuse committed work. */
	public static void main(String[] args) {
		if (args.length != 1) throw new IllegalArgumentException("expected: <store-directory>");
		var discover = new DiscoverFiles();
		var review = new ReviewFile();
		var report = new WriteReport();
		var definition = workflow(discover, review, report);
		var registry = StepRegistry.builder().register("discover", discover)
			.register("review", review).register("report", report).build();
		var compatibility = new ExecutionCompatibility("review-files", "review-files-0.13.0",
			Map.of("fixture", "deterministic-v1"));
		try (var runtime = DurableWorkflows.open(Path.of(args[0]).resolve("runs"), registry, compatibility)) {
			var run = runtime.start(definition, "revision-17", new ReviewRequest("revision-17"));
			var completed = runtime.resume(run.runId(), definition);
			if (completed.status() != RunSnapshot.Status.SUCCEEDED)
				throw new IllegalStateException("review failed: " + completed.status());
			var expected = new Report("revision-17", List.of(new FileReview("A.java", true),
				new FileReview("B.java", false)));
			var actual = runtime.result(run.runId(), definition);
			if (!expected.equals(actual)) throw new IllegalStateException("unexpected report: " + actual);
			System.out.println(actual);
		}
	}

}
