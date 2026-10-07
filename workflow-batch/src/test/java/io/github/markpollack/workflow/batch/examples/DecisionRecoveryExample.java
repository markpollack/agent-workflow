package io.github.markpollack.workflow.batch.examples;

import java.nio.file.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.requirement.Requirement;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.voting.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static io.github.markpollack.judge.verdict.Verdict.Conclusion.*;

/** Exhaustive ordinary and native choices with typed inputs and durable recovery. */
public final class DecisionRecoveryExample {

	private DecisionRecoveryExample() {
	}

	public record Subject(String id, String text) {
	}

	public record Explanation(Subject subject, Verdict assessment, Verdict.Conclusion conclusion) {
	}

	public record Reply(String id, String text) {
	}

	public enum Destination {

		SHORT, FULL

	}

	public record Settings(Path evidence) {
		public Map<String, String> facts() {
			return Map.of("evidence", evidence.toAbsolutePath().toString());
		}

		public void record(String name, String text) {
			try {
				Files.createDirectories(evidence);
				Files.writeString(evidence.resolve(name + ".calls"), text + "\n", StandardOpenOption.CREATE,
						StandardOpenOption.APPEND);
			}
			catch (IOException failure) {
				throw new UncheckedIOException(failure);
			}
		}
	}

	/** Configures a current native jury for this exact subject; retains both seats. */
	public static final class Assess implements Step<Subject, Verdict> {

		private final Settings settings;

		public Assess(Settings settings) {
			this.settings = settings;
		}

		public Verdict execute(StepContext context, Subject subject) {
			settings.record("jury", subject.id() + ":" + subject.text());
			var requirement = Requirement.text("citations", "1", "Include a verifiable citation");
			return SimpleJury.builder()
				.parallel(false)
				.judge("citation",
						() -> Judgment.error("citation service unavailable")
							.toBuilder()
							.metadata("subjectId", subject.id())
							.metadata("evidence", subject.text())
							.build()
							.forRequirement(requirement))
				.judge("length",
						() -> Judgment.pass("text is present").toBuilder().metadata("subjectId", subject.id()).build())
				.votingStrategy(new AllEligiblePassStrategy(ErrorHandling.PROPAGATE, ExclusionHandling.EXCLUDE))
				.build()
				.vote();
		}

	}

	public static final class Explain implements Step<Explanation, Reply> {

		private final Settings settings;

		public Explain(Settings settings) {
			this.settings = settings;
		}

		public Reply execute(StepContext context, Explanation input) {
			settings.record("explain",
					input.subject().id() + ":" + input.conclusion() + ":" + input.assessment().individual().size());
			return new Reply(input.subject().id(),
					input.conclusion() + ":" + input.assessment().judgment().reasoning());
		}

	}

	public static final class Choose implements Step<Reply, Destination> {

		private final Settings settings;

		public Choose(Settings settings) {
			this.settings = settings;
		}

		public Destination execute(StepContext context, Reply input) {
			settings.record("choose", input.id());
			return Destination.FULL;
		}

	}

	public static final class Render implements Step<Reply, Reply> {

		private final Settings settings;

		private final String style;

		public Render(Settings settings, String style) {
			this.settings = settings;
			this.style = style;
		}

		public Reply execute(StepContext context, Reply input) {
			settings.record(style, input.id() + ":" + input.text());
			return new Reply(input.id(), style + ":" + input.text());
		}

	}

	public static final class Publish implements Step<Reply, Reply> {

		private final Settings settings;

		public Publish(Settings settings) {
			this.settings = settings;
		}

		public Reply execute(StepContext context, Reply input) {
			settings.record("publish", input.id() + ":" + input.text());
			return input;
		}

	}

	public static ValidatedWorkflow workflow(Assess assess, Explain explain, Choose choose, Render shortForm,
			Render fullForm, Publish publish) {
		return Workflows.define("explain-assessment")
			.verdict("quality", assess)
			.when(PASS)
			.then(explain)
			.when(FAIL)
			.then(explain)
			.when(INCONCLUSIVE)
			.then(explain)
			.when(NOT_APPLICABLE)
			.then(explain)
			.end()
			.decision("destination", choose)
			.when(Destination.SHORT)
			.then(shortForm)
			.when(Destination.FULL)
			.then(fullForm)
			.end()
			.then(publish)
			.terminate(SUCCEEDED)
			.build();
	}

	public static void main(String[] args) {
		var settings = new Settings(Path.of(args[0]));
		var assess = new Assess(settings);
		var explain = new Explain(settings);
		var choose = new Choose(settings);
		var shortForm = new Render(settings, "short");
		var fullForm = new Render(settings, "full");
		var publish = new Publish(settings);
		var workflow = workflow(assess, explain, choose, shortForm, fullForm, publish);
		var registry = StepRegistry.of(Map.of("assessment", assess, "explanation", explain, "choice", choose, "short",
				shortForm, "full", fullForm, "publish", publish));
		try (var runtime = DurableWorkflows.open(settings.evidence().resolve("runs"), registry,
				new ExecutionCompatibility("decision-example", "v1", settings.facts()))) {
			var run = runtime.start(workflow, "subject-42", new Subject("subject-42", "original subject"));
			var end = runtime.resume(run.runId(), workflow);
			System.out.println(end.status() + " " + runtime.result(run.runId(), workflow));
		}
	}

}
