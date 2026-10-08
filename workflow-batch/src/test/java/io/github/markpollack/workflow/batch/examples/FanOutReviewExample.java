package io.github.markpollack.workflow.batch.examples;

import java.util.List;
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

}
