package io.github.markpollack.workflow.batch.durable;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.judgment.*;
import io.github.markpollack.judge.provenance.Invocation;
import io.github.markpollack.judge.requirement.Requirement;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.*;
import static io.github.markpollack.judge.verdict.Verdict.Conclusion.*;
import static org.assertj.core.api.Assertions.*;

class NativeVerdictExecutionTest {

	@TempDir
	Path directory;

	public record Subject(String id, String text) {
	}

	public record Reply(String id, String explanation) {
	}

	public record ExplanationInput(Subject subject, Verdict assessment, Verdict.Conclusion conclusion) {
	}

	static final class Assess implements Step<Subject, Verdict> {

		final JudgmentStatus status;

		int calls;

		Assess(JudgmentStatus status) {
			this.status = status;
		}

		public Verdict execute(StepContext context, Subject subject) {
			calls++;
			Judgment original = switch (status) {
				case PASS -> Judgment.pass("rubric satisfied");
				case FAIL -> Judgment.fail("required citation absent");
				case ABSTAIN -> Judgment.abstain("insufficient evidence");
				case ERROR -> Judgment.error("provider unavailable");
				case NOT_APPLICABLE -> Judgment.notApplicable("outside rubric");
			};
			original = original.toBuilder()
				.metadata("subjectId", subject.id())
				.metadata("evidence", List.of("citation-7", "citation-11"))
				.build()
				.forRequirement(Requirement.text("rubric", "1", "Use citations"))
				.withInvocation(new Invocation("observation-" + subject.id(), "fixture/v1",
						status != JudgmentStatus.ERROR, "model-fixture", 7,
						Map.of("subject", subject.id(), "details", subject.text()), List.of()));
			return Verdict.observed("rubric", original,
					status == JudgmentStatus.NOT_APPLICABLE ? "outside rubric" : null);
		}

	}

	static final class Explain implements Step<ExplanationInput, Reply> {

		int calls;

		public Reply execute(StepContext context, ExplanationInput input) {
			calls++;
			assertThat(input.assessment().individual().getFirst().metadata()).containsEntry("subjectId",
					input.subject().id());
			assertThat(io.github.markpollack.judge.verdict.InvocationRecords.of(input.assessment())).hasSize(1);
			return new Reply(input.subject().id(),
					input.conclusion() + ":" + input.assessment().judgment().reasoning());
		}

	}

	static ValidatedWorkflow workflow(Step<?, Verdict> assessment, Explain explain) {
		return Workflows.define("judged")
			.verdict("quality", assessment)
			.when(PASS)
			.then(explain)
			.terminate(SUCCEEDED)
			.when(FAIL)
			.then(explain)
			.terminate(SUCCEEDED)
			.when(INCONCLUSIVE)
			.then(explain)
			.terminate(SUCCEEDED)
			.when(NOT_APPLICABLE)
			.then(explain)
			.terminate(SUCCEEDED)
			.end()
			.build();
	}

	static ExecutionCompatibility deployment() {
		return new ExecutionCompatibility("native-verdict", "v1", Map.of());
	}

	@ParameterizedTest
	@EnumSource(JudgmentStatus.class)
	void fourNativeRoutesRetainErrorsAbstentionsSubjectAndEvidence(JudgmentStatus status) {
		var assess = new Assess(status);
		var explain = new Explain();
		var workflow = workflow(assess, explain);
		var registry = StepRegistry.of(Map.of("assessment", assess, "explanation", explain));
		var expected = switch (status) {
			case PASS -> PASS;
			case FAIL -> FAIL;
			case NOT_APPLICABLE -> NOT_APPLICABLE;
			case ERROR, ABSTAIN -> INCONCLUSIVE;
		};
		try (var runtime = DurableWorkflows.open(directory.resolve(status.name()), registry, deployment())) {
			String id = runtime.start(workflow, "subject", new Subject("story-42", "original text")).runId();
			var end = runtime.resume(id, workflow);
			assertThat(end.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(end.decisions()).hasSize(1);
			assertThat(end.decisions().getFirst().outcome()).isEqualTo(expected.name());
			assertThat(runtime.result(id, workflow)).isInstanceOf(Reply.class);
			var payload = end.values()
				.stream()
				.filter(v -> v.declaredType().equals(Verdict.class.getTypeName()))
				.findFirst()
				.orElseThrow();
			var retained = (Verdict) new TypeContracts().decodeBytes(payload.payload(), Verdict.class);
			assertThat(retained.judgment().status()).isEqualTo(status);
			assertThat(retained.individual().getFirst().metadata()).containsEntry("evidence",
					List.of("citation-7", "citation-11"));
			assertThat(io.github.markpollack.judge.verdict.InvocationRecords.of(retained).getFirst().nativeFacts())
				.containsEntry("subject", "story-42");
			assertThat(retained.individual().getFirst().requirement().id()).isEqualTo("rubric");
			assertThat(assess.calls).isEqualTo(1);
			assertThat(explain.calls).isEqualTo(1);
		}
	}

	@Test
	void freshCompatibleInstancesRecoverAcceptedAssessmentAndExactArmInput() {
		Path file = directory.resolve("recover");
		String id;
		var assess = new Assess(JudgmentStatus.ERROR);
		var explain = new Explain();
		var workflow = workflow(assess, explain);
		try (var runtime = DurableWorkflows.open(file,
				StepRegistry.of(Map.of("assessment", assess, "explanation", explain)), deployment())) {
			id = runtime.start(workflow, "subject", new Subject("s", "unchanged")).runId();
			var saved = runtime.advance(id, workflow);
			assertThat(saved.decisions().getFirst().outcome()).isEqualTo("INCONCLUSIVE");
			assertThat(saved.decisions().getFirst().armInput()).isEqualTo(saved.invocations().getLast().inputValue());
			assertThat(explain.calls).isZero();
		}
		var freshAssess = new Assess(JudgmentStatus.ERROR);
		var freshExplain = new Explain();
		var fresh = workflow(freshAssess, freshExplain);
		try (var runtime = DurableWorkflows.open(file,
				StepRegistry.of(Map.of("assessment", freshAssess, "explanation", freshExplain)), deployment())) {
			assertThat(runtime.resume(id, fresh).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(freshAssess.calls).isZero();
			assertThat(freshExplain.calls).isEqualTo(1);
			assertThat(runtime.result(id, fresh)).isEqualTo(new Reply("s", "INCONCLUSIVE:provider unavailable"));
		}
	}

	static final class Throwing implements Step<Subject, Verdict> {

		public Verdict execute(StepContext context, Subject subject) {
			throw new IllegalStateException("evaluation call failed");
		}

	}

	static final class Invalid implements Step<Subject, Verdict> {

		public Verdict execute(StepContext context, Subject subject) {
			return Verdict.of(Judgment.pass("unsupported aggregate"),
					Map.of("constituent", Judgment.fail("contradictory constituent")));
		}

	}

	@Test
	void thrownAndInvalidAssessmentsFailDistinctlyWithoutInventingConclusion() {
		List<Step<Subject, Verdict>> assessments = List.of(new Throwing(), new Invalid());
		for (int i = 0; i < assessments.size(); i++) {
			var assess = assessments.get(i);
			var explain = new Explain();
			var workflow = workflow(assess, explain);
			try (var runtime = DurableWorkflows.open(directory.resolve("failure" + i),
					StepRegistry.of(Map.of("assessment", assess, "explanation", explain)), deployment())) {
				var run = runtime.start(workflow, "subject", new Subject("s", "x"));
				var end = runtime.resume(run.runId(), workflow);
				assertThat(end.status()).isEqualTo(RunSnapshot.Status.FAILED);
				assertThat(end.reason().code()).isEqualTo(i == 0 ? "ASSESSMENT_FAILED" : "ASSESSMENT_INVALID");
				assertThat(end.decisions()).isEmpty();
				assertThat(explain.calls).isZero();
				assertThat(end.values()).hasSize(1);
			}
		}
	}

	static final class Panel implements Step<Subject, Verdict> {

		Verdict emitted;

		public Verdict execute(StepContext context, Subject subject) {
			emitted = io.github.markpollack.judge.jury.SimpleJury.builder()
				.parallel(false)
				.judge("citation",
						() -> Judgment.error("provider timeout").toBuilder().metadata("subject", subject.id()).build())
				.judge("wording",
						() -> Judgment.abstain("ambiguous wording")
							.toBuilder()
							.metadata("evidence", subject.text())
							.build())
				.votingStrategy(new io.github.markpollack.judge.voting.AllEligiblePassStrategy(
						io.github.markpollack.judge.voting.ErrorHandling.PROPAGATE,
						io.github.markpollack.judge.voting.ExclusionHandling.EXCLUDE))
				.build()
				.vote();
			return emitted;
		}

	}

	@Test
	void completePanelCompositionRoundTripsWithoutLosingErrorOrAbstention() {
		var panel = new Panel();
		var explain = new Explain();
		var workflow = workflow(panel, explain);
		try (var runtime = DurableWorkflows.open(directory.resolve("panel"),
				StepRegistry.of(Map.of("panel", panel, "explain", explain)), deployment())) {
			String id = runtime.start(workflow, "subject", new Subject("s", "original evidence")).runId();
			var accepted = runtime.advance(id, workflow);
			var payload = accepted.values()
				.stream()
				.filter(v -> v.declaredType().equals(Verdict.class.getTypeName()))
				.findFirst()
				.orElseThrow();
			var retained = (Verdict) new TypeContracts().decodeBytes(payload.payload(), Verdict.class);
			var codec = new io.github.markpollack.judge.serialization.VerdictCodec();
			assertThat(codec.write(retained)).isEqualTo(codec.write(panel.emitted));
			assertThat(retained.seats()).hasSize(2);
			assertThat(retained.declaredCardinality()).isEqualTo(2);
			assertThat(retained.rule()).isNotNull();
			assertThat(retained.provenance()).isEqualTo(panel.emitted.provenance());
			assertThat(retained.individual()).extracting(Judgment::status)
				.containsExactly(JudgmentStatus.ERROR, JudgmentStatus.ABSTAIN);
			assertThat(accepted.decisions().getFirst().outcome()).isEqualTo("INCONCLUSIVE");
		}
	}

	static final class CompositeAssessment implements Step<Subject, Verdict> {

		Verdict emitted;

		public Verdict execute(StepContext context, Subject subject) {
			var original = new Assess(JudgmentStatus.ERROR).execute(context, subject);
			emitted = io.github.markpollack.judge.jury.Juries.meta(
					new io.github.markpollack.judge.voting.AllEligiblePassStrategy(
							io.github.markpollack.judge.voting.ErrorHandling.PROPAGATE,
							io.github.markpollack.judge.voting.ExclusionHandling.EXCLUDE),
					new io.github.markpollack.judge.jury.NamedJury("observation", () -> original),
					new io.github.markpollack.judge.jury.NamedJury("transport", () -> {
						throw new IllegalStateException("connection failed");
					}))
				.vote();
			return emitted;
		}

	}

	@Test
	void nativeCompositeStageFailuresAndNestedObservationsStayInAcceptedAssessment() {
		var assess = new CompositeAssessment();
		var explain = new Explain();
		var workflow = workflow(assess, explain);
		try (var runtime = DurableWorkflows.open(directory.resolve("native-composition"),
				StepRegistry.of(Map.of("assess", assess, "explain", explain)), deployment())) {
			String id = runtime.start(workflow, "subject", new Subject("s", "original")).runId();
			var accepted = runtime.advance(id, workflow);
			var payload = accepted.values()
				.stream()
				.filter(v -> v.declaredType().equals(Verdict.class.getTypeName()))
				.findFirst()
				.orElseThrow();
			var retained = (Verdict) new TypeContracts().decodeBytes(payload.payload(), Verdict.class);
			var codec = new io.github.markpollack.judge.serialization.VerdictCodec();
			assertThat(codec.write(retained)).isEqualTo(codec.write(assess.emitted));
			assertThat(retained.compositeAttempts()).hasSize(2);
			assertThat(retained.compositeAttempts().getFirst().verdict().individual().getFirst().metadata())
				.containsEntry("subjectId", "s");
			assertThat(retained.compositeAttempts().getLast().failure()).isNotNull();
			assertThat(io.github.markpollack.judge.verdict.InvocationRecords.of(retained)).hasSize(1);
			assertThat(accepted.decisions().getFirst().outcome()).isEqualTo("INCONCLUSIVE");
		}
	}

}
