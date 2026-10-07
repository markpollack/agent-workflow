package io.github.markpollack.workflow.batch.examples;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;

/** Deterministic assessments keep business rejection distinct from execution failure. */
public final class ParallelAssessmentExample {

	private ParallelAssessmentExample() {
	}

	public record PullRequest(String number) {
	}

	public record Revision(String commit) {
	}

	public record BackportInput(PullRequest request, Revision revision) {
	}

	public record QualityAssessment(boolean acceptable, String revision) {
	}

	public record BackportAssessment(boolean suitable, String request) {
	}

	public record ReportInput(PullRequest request, QualityAssessment quality, BackportAssessment backport) {
	}

	public record Report(String text) {
	}

	public static class FetchRevision implements Step<PullRequest, Revision> {

		public Revision execute(StepContext context, PullRequest input) {
			return new Revision("sha-" + input.number());
		}

	}

	public static class AssessQuality implements Step<Revision, QualityAssessment> {

		public QualityAssessment execute(StepContext context, Revision input) {
			return new QualityAssessment(false, input.commit());
		}

	}

	public static class AssessBackport implements Step<BackportInput, BackportAssessment> {

		public BackportAssessment execute(StepContext context, BackportInput input) {
			return new BackportAssessment(true, input.request().number() + ":" + input.revision().commit());
		}

	}

	public static class WriteReport implements Step<ReportInput, Report> {

		public Report execute(StepContext context, ReportInput input) {
			return new Report(input.request().number() + ":quality=" + input.quality().acceptable() + ":backport="
					+ input.backport().suitable());
		}

	}

	public static ValidatedWorkflow workflow(FetchRevision fetch, AssessQuality quality, AssessBackport backport,
			WriteReport report) {
		return Workflows.define("parallel-assessments")
			.then(fetch)
			.parallel("assessments")
			.allSuccessful()
			.branch("quality")
			.then(quality)
			.branch("backport")
			.then(backport)
			.end()
			.then(report)
			.terminate(SUCCEEDED)
			.build();
	}

}
