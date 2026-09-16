package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.result.Judgment;

import java.util.Objects;
import java.util.function.BiFunction;

import io.github.markpollack.judge.context.JudgmentContext;

/**
 * Three-tier quality gate: PASS if score &gt;= highThreshold, ESCALATE if &gt;= lowThreshold, FAIL otherwise.
 * <p>
 * Like {@link JudgeGate}, backed by an {@code agent-judge} Jury, and like {@link JudgeGate} the
 * {@link JudgmentContext} the jury votes on is produced by a required <em>context mapper</em> — see
 * {@link JudgeGate} for why. The ESCALATE path is for borderline results that need human review.
 *
 * <h2>Aggregates that carry no score</h2>
 * A status-only PASS or FAIL is tiered through its derived {@code 1.0}/{@code 0.0} view. The two
 * scoreless outcomes part company here, because this gate has a human tier that {@link JudgeGate}
 * does not: an <em>abstention</em> is a subject this jury cannot speak to, which is precisely what
 * the escalation path is for, so it escalates rather than terminating. An <em>evaluation error</em>
 * means the jury never ran to completion; escalating it would present a non-result to a reviewer as
 * a borderline result, so the evaluation comes back {@link GateAssessment.Inconclusive} and the
 * engine terminates the attempt through its error path once it has recorded the verdict. A
 * <em>not-applicable</em> aggregate is treated the same way: the jury declared the question should
 * not have been asked, which is neither a borderline result for a reviewer nor a pass.
 *
 * @param <O> the type of output being evaluated
 */
public class TieredGate<O> implements Gate<O> {

    private final Jury jury;
    private final double highThreshold;
    private final double lowThreshold;
    private final BiFunction<AgentContext, O, JudgmentContext> contextMapper;

    /**
     * @param jury          the jury that votes on the mapped context
     * @param highThreshold PASS if the aggregated score &gt;= this value
     * @param lowThreshold  ESCALATE if the aggregated score &gt;= this value (and &lt; highThreshold)
     * @param contextMapper maps {@code (AgentContext, output)} to the {@link JudgmentContext} the jury
     *                      votes on — populate {@code metadata}/{@code workspace} here
     */
    public TieredGate(Jury jury, double highThreshold, double lowThreshold,
            BiFunction<AgentContext, O, JudgmentContext> contextMapper) {
        this.jury = Objects.requireNonNull(jury, "jury");
        if (lowThreshold > highThreshold) {
            throw new IllegalArgumentException(
                    "lowThreshold (" + lowThreshold + ") must be <= highThreshold (" + highThreshold + ")");
        }
        this.highThreshold = highThreshold;
        this.lowThreshold = lowThreshold;
        this.contextMapper = Objects.requireNonNull(contextMapper, "contextMapper");
    }

    @Override
    public GateAssessment evaluate(AgentContext ctx, O output) {
        JudgmentContext judgmentCtx = this.contextMapper.apply(ctx, output);

        Verdict verdict = jury.vote(judgmentCtx);
        Judgment aggregate = verdict.aggregated();

        return switch (aggregate.status()) {
            case PASS, FAIL -> new GateAssessment.Decided(tier(JudgeGate.comparableScore(aggregate)), verdict);
            // A subject this jury cannot speak to is what the human tier exists for.
            case ABSTAIN -> new GateAssessment.Decided(GateDecision.ESCALATE, verdict);
            case NOT_APPLICABLE, ERROR -> new GateAssessment.Inconclusive(verdict,
                    "jury returned " + aggregate.status() + ", so no finding exists to tier against thresholds "
                            + lowThreshold + "/" + highThreshold + ": " + aggregate.reasoning());
        };
    }

    private GateDecision tier(double score) {
        if (score >= highThreshold) return GateDecision.PASS;
        if (score >= lowThreshold) return GateDecision.ESCALATE;
        return GateDecision.FAIL;
    }
}
