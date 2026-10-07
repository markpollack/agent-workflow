package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;

import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.judgment.Judgment;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.BiFunction;

/**
 * Application score-policy gate using a Jury configured for each supplied output.
 * PASS/FAIL findings are compared by effective score. ABSTAIN, ERROR and NOT_APPLICABLE
 * retain the assessment in an Inconclusive result. This policy gate is independent of
 * native Conclusion routing in the durable workflow DSL.
 *
 * @param <O> application output under assessment
 */
public class JudgeGate<O> implements Gate<O> {

	private final double threshold;

	private final BiFunction<AgentContext, O, Jury> juryFactory;

	/**
	 * @param threshold minimum effective score for passage
	 * @param juryFactory configures judges with this context and exact output
	 */
	public JudgeGate(double threshold, BiFunction<AgentContext, O, Jury> juryFactory) {

		this.threshold = threshold;
		this.juryFactory = Objects.requireNonNull(juryFactory, "juryFactory");
	}

	@Override
	public GateAssessment evaluate(AgentContext ctx, O output) {
		Jury jury = Objects.requireNonNull(juryFactory.apply(ctx, output));
		Verdict verdict = jury.vote();
		Judgment aggregate = verdict.judgment();
		return switch (aggregate.status()) {
			// The jury reached a finding. A measured score is compared directly; a
			// status-only
			// finding is compared through its derived 1.0/0.0 view.
			case PASS, FAIL -> new GateAssessment.Decided(
					comparableScore(aggregate) >= threshold ? GateDecision.PASS : GateDecision.FAIL, verdict);
			// No finding exists to compare, and zero is a real assessment rather than the
			// absence
			// of one. The evaluation is returned as inconclusive so the engine records it
			// and then
			// terminates the attempt through its error path.
			case ABSTAIN, NOT_APPLICABLE,
					ERROR ->
				new GateAssessment.Inconclusive(verdict,
						"jury returned " + aggregate.status() + ", so no pass/fail finding exists to compare "
								+ "against threshold " + threshold + ": " + aggregate.reasoning());
		};
	}

	/**
	 * The numeric contribution of a judgment that reached a finding.
	 * <p>
	 * Reading {@code status} first is what makes this total: {@code effectiveScore()} is
	 * empty exactly for the statuses this method is never called with.
	 */
	static double comparableScore(Judgment aggregate) {
		OptionalDouble effective = aggregate.effectiveScore();
		if (effective.isEmpty()) {
			throw new IllegalStateException(
					"Judgment with status " + aggregate.status() + " carries no comparable score");
		}
		return effective.getAsDouble();
	}

}
