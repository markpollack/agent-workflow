package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.result.Judgment;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.BiFunction;

/**
 * Quality gate backed by an {@code agent-judge} {@link Jury}.
 * <p>
 * PASS if the aggregated score &gt;= threshold, FAIL otherwise.
 * The {@link Verdict} rides out on the returned {@link GateAssessment}, so the executor can write
 * it into {@link AgentContext#JUDGE_VERDICT} for the retry step.
 *
 * <h2>Aggregates that carry no score</h2>
 * A jury reports an outcome; it does not always report a measurement. A status-only PASS or
 * FAIL is compared through its derived {@code 1.0}/{@code 0.0} view, so a judge that passed
 * without measuring anything clears this gate. An abstention, a not-applicable exclusion or an
 * evaluation error is not a finding at all — there is nothing to compare against the threshold,
 * and treating it as a quality score of zero would turn "did not evaluate" into "evaluated badly" —
 * so the evaluation comes back {@link GateAssessment.Inconclusive} and the engine terminates the
 * attempt through its error path once it has recorded the verdict. A not-applicable aggregate is
 * not a pass either: routing an exclusion onward would let a jury clear the gate by declining the
 * question.
 *
 * <h2>Building the {@link JudgmentContext}</h2>
 * The jury votes on a {@link JudgmentContext} produced by a <em>context mapper</em> —
 * a {@code (AgentContext, O) -> JudgmentContext} function supplied <strong>at
 * construction (required)</strong>. This is deliberate: real judges read their typed
 * inputs from {@link JudgmentContext#metadata()} and/or {@link JudgmentContext#workspace()}
 * (build / coverage / quality judges all do), so the gate cannot build a usable context
 * generically — only the caller knows how to map the workflow {@link AgentContext} and the
 * gate output into the judge's inputs. Callers whose judges genuinely read nothing but
 * {@code agentOutput} may pass {@link #defaultContextMapper(String)} explicitly.
 *
 * @param <O> the type of output being evaluated
 */
public class JudgeGate<O> implements Gate<O> {

    private final Jury jury;
    private final double threshold;
    private final BiFunction<AgentContext, O, JudgmentContext> contextMapper;

    /**
     * @param jury          the jury that votes on the mapped context
     * @param threshold     PASS if the aggregated score &gt;= this value
     * @param contextMapper maps {@code (AgentContext, output)} to the {@link JudgmentContext} the jury
     *                      votes on — populate {@code metadata}/{@code workspace}/{@code status} here so
     *                      the judges receive their typed inputs
     */
    public JudgeGate(Jury jury, double threshold, BiFunction<AgentContext, O, JudgmentContext> contextMapper) {
        this.jury = Objects.requireNonNull(jury, "jury");
        this.threshold = threshold;
        this.contextMapper = Objects.requireNonNull(contextMapper, "contextMapper");
    }

    @Override
    public GateAssessment evaluate(AgentContext ctx, O output) {
        JudgmentContext judgmentCtx = this.contextMapper.apply(ctx, output);
        Verdict verdict = jury.vote(judgmentCtx);
        Judgment aggregate = verdict.aggregated();
        return switch (aggregate.status()) {
            // The jury reached a finding. A measured score is compared directly; a status-only
            // finding is compared through its derived 1.0/0.0 view.
            case PASS, FAIL -> new GateAssessment.Decided(
                    comparableScore(aggregate) >= threshold ? GateDecision.PASS : GateDecision.FAIL,
                    verdict);
            // No finding exists to compare, and zero is a real assessment rather than the absence
            // of one. The evaluation is returned as inconclusive so the engine records it and then
            // terminates the attempt through its error path.
            case ABSTAIN, NOT_APPLICABLE, ERROR -> new GateAssessment.Inconclusive(verdict,
                    "jury returned " + aggregate.status() + ", so no pass/fail finding exists to compare "
                            + "against threshold " + threshold + ": " + aggregate.reasoning());
        };
    }

    /**
     * The numeric contribution of a judgment that reached a finding.
     * <p>
     * Reading {@code status} first is what makes this total: {@code effectiveScore()} is empty
     * exactly for the statuses this method is never called with.
     */
    static double comparableScore(Judgment aggregate) {
        OptionalDouble effective = aggregate.effectiveScore();
        if (effective.isEmpty()) {
            throw new IllegalStateException(
                    "Judgment with status " + aggregate.status() + " carries no comparable score");
        }
        return effective.getAsDouble();
    }

    /**
     * A minimal context mapper that sets only {@code goal} and {@code agentOutput = output.toString()}
     * (with {@code executionTime = ZERO}, {@code startedAt = now()}); it populates no metadata or
     * workspace. Use only when every judge in the jury reads nothing but {@code agentOutput}. Shared
     * with {@link TieredGate}.
     *
     * @param goal the {@link JudgmentContext#goal()} label
     * @param <O>  the type of output being evaluated
     */
    public static <O> BiFunction<AgentContext, O, JudgmentContext> defaultContextMapper(String goal) {
        return (ctx, output) -> JudgmentContext.builder()
                .goal(goal)
                .agentOutput(output != null ? output.toString() : "")
                .executionTime(Duration.ZERO)
                .startedAt(Instant.now())
                .build();
    }
}
