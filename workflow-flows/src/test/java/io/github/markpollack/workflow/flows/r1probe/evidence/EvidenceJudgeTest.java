package io.github.markpollack.workflow.flows.r1probe.evidence;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.judgment.Judgment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;
class EvidenceJudgeTest {
    @ParameterizedTest @EnumSource(Verdict.Conclusion.class)
    void allCurrentNativeConclusionsPreserveAssessment(Verdict.Conclusion conclusion) {
        var result=NativeJudgeProbe.assess(NativeJudgeProbe.jury(conclusion));
        assertThat(result.route()).isEqualTo(conclusion);assertThat(result.failure()).isEmpty();
        assertThat(result.verdict().individual()).hasSize(1);
    }
    @Test void thrownCallIsNotAnInconclusiveAssessment() {
        var result=NativeJudgeProbe.assess(NativeJudgeProbe.throwing());
        assertThat(result.verdict()).isNull();assertThat(result.route()).isNull();assertThat(result.failure()).contains("jury unavailable");
    }
    @Test void errorsAndAbstentionsRemainDifferentNativeEvidence() {
        var error=Verdict.single("error",Judgment.error("provider unavailable"));
        var abstain=Verdict.single("abstain",Judgment.abstain("insufficient evidence"));
        assertThat(error.requireUsable().conclusion()).isEqualTo(Verdict.Conclusion.INCONCLUSIVE);
        assertThat(abstain.requireUsable().conclusion()).isEqualTo(Verdict.Conclusion.INCONCLUSIVE);
        assertThat(error.judgment()).isNotEqualTo(abstain.judgment());
    }
}
