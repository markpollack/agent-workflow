package io.github.markpollack.workflow.flows.workflow;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.verdict.Verdict;
import static org.assertj.core.api.Assertions.*;

class NativeGateAdapterTest {

	@Test
	void currentProducerFactoryReceivesExactOutputAndKeepsScorePolicySeparate() {
		var context = AgentContext.create();
		var subject = new Object();
		var observed = new AtomicReference<Object>();
		var assessment = Verdict.single("judge", Judgment.verdict(true).score(0.6).reasoning("accepted").build());
		java.util.function.BiFunction<AgentContext, Object, Jury> factory = (actual, output) -> {
			assertThat(actual).isSameAs(context);
			observed.set(output);
			return () -> assessment;
		};
		var binary = (GateAssessment.Decided) new JudgeGate<>(0.7, factory).evaluate(context, subject);
		assertThat(observed.get()).isSameAs(subject);
		assertThat(binary.decision()).isEqualTo(GateDecision.FAIL);
		assertThat(binary.verdict()).isSameAs(assessment);
		assertThat(assessment.conclusion()).isEqualTo(Verdict.Conclusion.PASS);
		var tiered = (GateAssessment.Decided) new TieredGate<>(0.7, 0.4, factory).evaluate(context, subject);
		assertThat(tiered.decision()).isEqualTo(GateDecision.ESCALATE);
		assertThat(tiered.verdict()).isSameAs(assessment);
	}

	@Test
	void abstentionAndErrorRetainDistinctLegacyPolicyOutcomes() {
		var context = AgentContext.create();
		var abstain = Verdict.single("judge", Judgment.abstain("need evidence"));
		var error = Verdict.single("judge", Judgment.error("provider failed"));
		assertThat(new JudgeGate<String>(0.7, (ctx, output) -> () -> abstain).evaluate(context, "s"))
			.isInstanceOf(GateAssessment.Inconclusive.class);
		assertThat(((GateAssessment.Decided) new TieredGate<String>(0.7, 0.4, (ctx, output) -> () -> abstain)
			.evaluate(context, "s")).decision()).isEqualTo(GateDecision.ESCALATE);
		assertThat(new TieredGate<String>(0.7, 0.4, (ctx, output) -> () -> error).evaluate(context, "s"))
			.isInstanceOf(GateAssessment.Inconclusive.class);
		assertThat(abstain.conclusion()).isEqualTo(error.conclusion()).isEqualTo(Verdict.Conclusion.INCONCLUSIVE);
	}

}
