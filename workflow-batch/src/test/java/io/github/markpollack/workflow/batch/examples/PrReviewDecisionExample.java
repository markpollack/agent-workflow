package io.github.markpollack.workflow.batch.examples;

import java.nio.file.Path;
import java.util.Map;

import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.voting.AllEligiblePassStrategy;
import io.github.markpollack.judge.voting.ErrorHandling;
import io.github.markpollack.judge.voting.ExclusionHandling;
import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;

import static io.github.markpollack.judge.verdict.Verdict.Conclusion.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;

/** A deterministic PR-review fixture using the real native jury and durable runtime. */
public final class PrReviewDecisionExample {

	private PrReviewDecisionExample() {
	}

	public record PullRequest(String repository, int number) {
	}

	public record Diff(String revision, String text) {
	}

	public record ReviewInput(PullRequest subject, Diff diff, Verdict assessment, Verdict.Conclusion conclusion) {
	}

	public record Report(String subject, String text) {
	}

	public enum Format {

		SHORT, FULL

	}

	public static final class Fetch implements Step<PullRequest, Diff> {

		public Diff execute(StepContext context, PullRequest subject) {
			return new Diff("fixture-revision-42", "Add a citation to the review report");
		}

	}

	public static final class Assess implements Step<Diff, Verdict> {

		private int calls;

		public int calls() {
			return calls;
		}

		public Verdict execute(StepContext context, Diff diff) {
			calls++;
			return SimpleJury.builder()
				.parallel(false)
				.judge("coverage",
						() -> Judgment.pass("Citation covered")
							.toBuilder()
							.metadata("revision", diff.revision())
							.build())
				.judge("security",
						() -> Judgment.error("Fixture: security provider unavailable")
							.toBuilder()
							.metadata("evidence", diff.text())
							.build())
				.votingStrategy(new AllEligiblePassStrategy(ErrorHandling.PROPAGATE, ExclusionHandling.EXCLUDE))
				.build()
				.vote();
		}

	}

	public static final class Explain implements Step<ReviewInput, Report> {

		private final String heading;

		public Explain(String heading) {
			this.heading = heading;
		}

		public Report execute(StepContext context, ReviewInput input) {
			String evidence = input.assessment()
				.individual()
				.stream()
				.map(judgment -> judgment.status() + ": " + judgment.reasoning())
				.collect(java.util.stream.Collectors.joining("\n"));
			return new Report(input.subject().repository() + "#" + input.subject().number(),
					heading + "\n" + input.diff().revision() + "\n" + input.conclusion() + "\n" + evidence);
		}

	}

	public static final class ChooseFormat implements Step<Report, Format> {

		public Format execute(StepContext context, Report report) {
			return Format.FULL;
		}

	}

	public static final class Render implements Step<Report, Report> {

		private final String format;

		public Render(String format) {
			this.format = format;
		}

		public Report execute(StepContext context, Report report) {
			return new Report(report.subject(), format + "\n" + report.text());
		}

	}

	public static ValidatedWorkflow workflow(Fetch fetch, Assess assess, Explain passed, Explain failed,
			Explain inconclusive, Explain excluded, ChooseFormat choose, Render shortForm, Render fullForm) {
		return Workflows.define("pr-review-fixture")
			.then(fetch)
			.verdict("review-quality", assess)
			.when(PASS)
			.then(passed)
			.when(FAIL)
			.then(failed)
			.when(INCONCLUSIVE)
			.then(inconclusive)
			.when(NOT_APPLICABLE)
			.then(excluded)
			.end()
			.decision("report-format", choose)
			.when(Format.SHORT)
			.then(shortForm)
			.when(Format.FULL)
			.then(fullForm)
			.end()
			.terminate(SUCCEEDED)
			.build();
	}

	/**
	 * Reopens after assessment acceptance; the replacement assessment must not execute.
	 */
	public static Report run(Path store) {
		var fetch = new Fetch();
		var assess = new Assess();
		var passed = new Explain("Review passed");
		var failed = new Explain("Review failed");
		var inconclusive = new Explain("Review needs more evidence");
		var excluded = new Explain("Review outside scope");
		var choose = new ChooseFormat();
		var shortForm = new Render("Short report");
		var fullForm = new Render("Full report");
		var workflow = workflow(fetch, assess, passed, failed, inconclusive, excluded, choose, shortForm, fullForm);
		var registry = StepRegistry
			.of(Map.of("fetch", fetch, "assess", assess, "passed", passed, "failed", failed, "inconclusive",
					inconclusive, "excluded", excluded, "format", choose, "short", shortForm, "full", fullForm));
		var compatibility = new ExecutionCompatibility("pr-review-fixture", "v1", Map.of("provider", "deterministic"));
		String id;
		try (var runtime = DurableWorkflows.open(store, registry, compatibility)) {
			id = runtime.start(workflow, "review-42", new PullRequest("example/repository", 42)).runId();
			runtime.advance(id, workflow); // fetch
			var accepted = runtime.advance(id, workflow); // jury and exact branch input
															// accepted atomically
			if (accepted.decisions().size() != 1 || assess.calls() != 1)
				throw new IllegalStateException("Assessment was not accepted");
		}
		var replacement = new Assess();
		var recovered = workflow(fetch, replacement, passed, failed, inconclusive, excluded, choose, shortForm,
				fullForm);
		var recoveredRegistry = StepRegistry
			.of(Map.of("fetch", fetch, "assess", replacement, "passed", passed, "failed", failed, "inconclusive",
					inconclusive, "excluded", excluded, "format", choose, "short", shortForm, "full", fullForm));
		try (var runtime = DurableWorkflows.open(store, recoveredRegistry, compatibility)) {
			runtime.resume(id, recovered);
			if (replacement.calls() != 0)
				throw new IllegalStateException("Accepted jury repeated");
			return (Report) runtime.result(id, recovered);
		}
	}

	public static void main(String[] args) {
		System.out.println(run(Path.of(args[0])));
	}

}
