package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.judge.verdict.Verdict;

/**
 * Ends a run whose gate reached no finding, after the engine has recorded the evidence.
 *
 * <p>This is <em>transport</em>, not the evaluation model. The v1 engine routes and terminates
 * through exceptions, so an inconclusive {@link GateAssessment} needs a signal to travel on once
 * the former graph executor has captured the verdict, written it to the context and recorded the
 * gate's transition. What the engine knows is in the evidence it has already written; this only
 * carries the run out.
 *
 * <p>It exists so that {@link IllegalStateException} can go back to meaning what it means
 * everywhere else in the executor — a violated invariant, a graph the engine cannot walk. A jury
 * that abstains is neither; it is an ordinary outcome the engine is obliged to record and report.
 *
 * <p>The verdict travels with it so a caller that never sees the run's context can still read what
 * the jury concluded. Naming the failure — the UPPER_SNAKE vocabulary that distinguishes an
 * inconclusive evaluation from a failed one on the wire — belongs to the contract act that owns the
 * neutral result records, and is deliberately not coined here.
 */
public class GateAssessmentException extends RuntimeException {

    private final transient Verdict verdict;
    private final String nodeName;

    /**
     * @param nodeName the gate node the run stopped at
     * @param verdict  the verdict that reached no finding
     * @param reason   why the gate could not route it
     */
    public GateAssessmentException(String nodeName, Verdict verdict, String reason) {
        super("Gate '" + nodeName + "' reached no decision: " + reason);
        this.nodeName = nodeName;
        this.verdict = verdict;
    }

    /** The verdict that reached no finding. */
    public Verdict verdict() {
        return verdict;
    }

    /** The gate node the run stopped at. */
    public String nodeName() {
        return nodeName;
    }
}
