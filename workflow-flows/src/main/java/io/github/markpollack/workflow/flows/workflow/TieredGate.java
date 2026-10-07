package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.judgment.Judgment;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Application score-policy gate with pass, escalation and failure thresholds. ABSTAIN
 * escalates; ERROR and NOT_APPLICABLE retain the assessment as Inconclusive. The policy
 * remains separate from durable native Conclusion routing.
 *
 * @param <O> application output under assessment
 */
public class TieredGate<O> implements Gate<O> {

	private final double highThreshold;

	private final double lowThreshold;

	private final BiFunction<AgentContext, O, Jury> juryFactory;

	/**
	 * @param highThreshold minimum effective score for passage
	 * @param lowThreshold minimum effective score for escalation
	 * @param juryFactory configures judges with this context and exact output
	 */
	public TieredGate(double highThreshold, double lowThreshold, BiFunction<AgentContext, O, Jury> juryFactory) {

		if (lowThreshold > highThreshold) {
			throw new IllegalArgumentException(
					"lowThreshold (" + lowThreshold + ") must be <= highThreshold (" + highThreshold + ")");
		}
		this.highThreshold = highThreshold;
		this.lowThreshold = lowThreshold;
		this.juryFactory = Objects.requireNonNull(juryFactory, "juryFactory");
	}

	@Override
	public GateAssessment evaluate(AgentContext ctx, O output) {
		Jury jury = Objects.requireNonNull(juryFactory.apply(ctx, output));

		Verdict verdict = jury.vote();
		Judgment aggregate = verdict.judgment();

		return switch (aggregate.status()) {
			case PASS, FAIL -> new GateAssessment.Decided(tier(JudgeGate.comparableScore(aggregate)), verdict);
			// A subject this jury cannot speak to is what the human tier exists for.
			case ABSTAIN -> new GateAssessment.Decided(GateDecision.ESCALATE, verdict);
			case NOT_APPLICABLE,
					ERROR ->
				new GateAssessment.Inconclusive(verdict,
						"jury returned " + aggregate.status() + ", so no finding exists to tier against thresholds "
								+ lowThreshold + "/" + highThreshold + ": " + aggregate.reasoning());
		};
	}

	private GateDecision tier(double score) {
		if (score >= highThreshold)
			return GateDecision.PASS;
		if (score >= lowThreshold)
			return GateDecision.ESCALATE;
		return GateDecision.FAIL;
	}

}
