package io.github.markpollack.workflow.flows.r1probe.evidence;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.markpollack.judge.Judges;
import io.github.markpollack.judge.jury.ConsensusStrategy;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.DefectKind;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.ReadingSupport;
import io.github.markpollack.judge.jury.interpretation.VerdictReading;
import io.github.markpollack.judge.jury.interpretation.Verdicts;
import io.github.markpollack.judge.result.Judgment;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceJudgeTest {

	@ParameterizedTest
	@EnumSource(VerdictReading.class)
	void allNativeSupportedReadingsRouteWithoutCollapsingMeaning(VerdictReading reading) {
		var assessment = NativeJudgeProbe.assess(NativeJudgeProbe.jury(reading), NativeJudgeProbe.CONTEXT);
		assertThat(assessment.route()).isEqualTo(reading);
		assertThat(assessment.failure()).isNull();
		assertThat(assessment.interpretation().readingSupport()).isEqualTo(ReadingSupport.SUPPORTED);
		assertThat(assessment.interpretation().defects()).isEmpty();
		assertThat(assessment.verdict().individual()).hasSize(1);
		assertThat(assessment.verdict().seats()).hasSize(1);
		assertThat(assessment.verdict().aggregated().metadata()).containsKey(Judgment.AGGREGATION_KEY);
		assertThat(assessment.interpretation()).isEqualTo(Verdicts.interpret(assessment.verdict()));
	}

	@Test
	void missingReadingRefusesWithNativeDefectsAndOriginalStoredEvidence() {
		Map<String, Object> damaged = stored(NativeJudgeProbe.jury(VerdictReading.ACCEPTED).vote(NativeJudgeProbe.CONTEXT));
		aggregated(damaged).remove("status");
		Interpretation interpretation = Verdicts.interpret(damaged);
		assertThat(interpretation.reading()).isNull();
		assertThat(NativeJudgeProbe.route(interpretation)).isNull();
		assertThat(NativeJudgeProbe.failure(interpretation)).isEqualTo("missing native reading");
		assertThat(interpretation.defects()).anyMatch(defect -> defect.kind() == DefectKind.ABSENT);
		assertThat(damaged).containsKeys("individual", "seats", "decision", "compositeAttempts");
		assertThat(aggregated(damaged)).doesNotContainKey("status");
	}

	@Test
	void contradictedStatusNeverRoutesEvenWhenItsReadingIsAccepted() {
		Map<String, Object> damaged = stored(NativeJudgeProbe.jury(VerdictReading.REJECTED).vote(NativeJudgeProbe.CONTEXT));
		aggregated(damaged).put("status", "pass");
		Interpretation interpretation = Verdicts.interpret(damaged);
		assertThat(interpretation.reading()).isEqualTo(VerdictReading.ACCEPTED);
		assertThat(interpretation.readingSupport()).isEqualTo(ReadingSupport.CONTRADICTED);
		assertThat(NativeJudgeProbe.route(interpretation)).isNull();
		assertThat(NativeJudgeProbe.failure(interpretation)).contains("CONTRADICTED");
		assertThat(interpretation.defects()).anyMatch(defect -> defect.kind() == DefectKind.INCONSISTENT);
		assertThat(aggregated(damaged)).containsEntry("status", "pass");
	}

	@Test
	void handBuiltVerdictCannotBypassMissingAggregationEvidence() {
		Verdict verdict = Verdict.single("rubric", Judgment.pass("hand authored"));
		Interpretation interpretation = Verdicts.interpret(verdict);
		assertThat(interpretation.reading()).isEqualTo(VerdictReading.ACCEPTED);
		assertThat(interpretation.readingSupport()).isEqualTo(ReadingSupport.UNDETERMINED);
		assertThat(NativeJudgeProbe.route(interpretation)).isNull();
		assertThat(NativeJudgeProbe.failure(interpretation)).contains("UNDETERMINED");
		assertThat(interpretation.defects()).anyMatch(defect -> defect.field().equals("metadata.aggregation"));
		assertThat(verdict.individualByName()).containsEntry("rubric", verdict.aggregated());
	}

	@Test
	void exceptionWithoutVerdictRemainsExecutionFailure() {
		var assessment = NativeJudgeProbe.assess(NativeJudgeProbe.throwing(), NativeJudgeProbe.CONTEXT);
		assertThat(assessment.verdict()).isNull();
		assertThat(assessment.interpretation()).isNull();
		assertThat(assessment.route()).isNull();
		assertThat(assessment.failure()).isEqualTo("java.lang.IllegalStateException: jury unavailable");
	}

	@Test
	void judgeExceptionContainedByNativeJuryCanProduceSupportedNotAssessed() {
		AtomicInteger calls = new AtomicInteger();
		var jury = SimpleJury.builder().judge(Judges.named(context -> {
			assertThat(context).isSameAs(NativeJudgeProbe.CONTEXT);
			calls.incrementAndGet();
			throw new IllegalStateException("provider failed");
		}, "provider")).votingStrategy(new ConsensusStrategy()).parallel(false).build();
		var assessment = NativeJudgeProbe.assess(jury, NativeJudgeProbe.CONTEXT);
		assertThat(calls).hasValue(1);
		assertThat(assessment.route()).isEqualTo(VerdictReading.NOT_ASSESSED);
		assertThat(assessment.failure()).isNull();
		assertThat(assessment.verdict().individual().getFirst().reasoning()).contains("provider failed");
	}

	static Map<String, Object> stored(Verdict verdict) {
		return new ObjectMapper().convertValue(verdict, new TypeReference<>() { });
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> aggregated(Map<String, Object> verdict) {
		return (Map<String, Object>) verdict.get("aggregated");
	}

}
