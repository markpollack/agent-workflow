package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.result.JudgmentStatus;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * What one gate concluded about one output — either a routing decision, or the fact that no
 * decision exists to route on.
 *
 * <p>This makes {@link Gate#evaluate} total over the statuses of a returned {@link Verdict}. A jury
 * reports an outcome and does not always reach a finding: an abstention is a subject it cannot
 * speak to, a not-applicable exclusion is a question that should not have been asked, and an
 * evaluation error is a jury that never ran to completion. None is a pass and none is a fail, so
 * none can be spelled as a {@link GateDecision}. Unchecked failures that
 * prevent a jury from returning a verdict are outside this result algebra and may still propagate.
 *
 * <h2>It is public and in-process, and it is not the wire</h2>
 * This carries the Agent Judge {@link Verdict} whole rather than transcribing it. The translation
 * from a returned verdict is lossless by construction: nothing is extracted, projected or
 * summarised out of the framework's result, so no verdict fact is dropped on the way in. Neutral,
 * language-independent result records belong at the wire boundary. Reading the status of an
 * {@link Inconclusive} assessment means reading {@code verdict().aggregated().status()}, which is
 * where that fact already lives.
 *
 * <h2>It holds no policy</h2>
 * Which side of a threshold a score falls on, and what a gate does about an abstention, are the
 * gate's decisions. This type records what was decided; it never decides.
 *
 * <h2>Why it is not called {@code GateEvaluation}</h2>
 * That name is already spoken for. A ratified contract direction gives it to the neutral,
 * language-neutral wire record — a judge result plus gate mode, threshold, tier, execution
 * coordinates and outward outcome — which a later contract act will define in the spec module.
 * This is a smaller and different thing: what one gate concluded, in-process, on the way to a
 * routing decision. Two types under one name, one carrying a live framework object and one being
 * the wire, is exactly the confusion that act will have to keep apart.
 */
public sealed interface GateAssessment permits GateAssessment.Decided, GateAssessment.Inconclusive {

    /**
     * The jury verdict this evaluation came from, or {@code null} for a gate that consults no jury.
     * Never {@code null} for an {@link Inconclusive} evaluation — an evaluation can only be
     * inconclusive because some jury reached no finding.
     */
    @Nullable Verdict verdict();

    /**
     * The gate reached a routing decision.
     *
     * @param decision the outward decision the executor routes on
     * @param verdict  the verdict behind it, or {@code null} for a gate with no jury
     */
    record Decided(GateDecision decision, @Nullable Verdict verdict) implements GateAssessment {

        public Decided {
            Objects.requireNonNull(decision, "decision");
        }
    }

    /**
     * The gate reached no decision because the jury reached no finding.
     *
     * @param verdict the verdict, whose aggregate status says whether the jury abstained, excluded
     *                the subject as not applicable, or errored
     * @param reason  why this gate could not route the verdict, for the engine's diagnostics
     */
    record Inconclusive(Verdict verdict, String reason) implements GateAssessment {

        public Inconclusive {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(reason, "reason");
            JudgmentStatus status = verdict.aggregated().status();
            switch (status) {
                case ABSTAIN, NOT_APPLICABLE, ERROR -> {
                    // These statuses carry no pass/fail finding and therefore no routing decision.
                }
                case PASS, FAIL -> throw new IllegalArgumentException(
                        "Inconclusive assessment requires an ABSTAIN or ERROR verdict, not " + status);
            }
        }
    }

    /** A decision from a gate that consults no jury — an approval or a predicate. */
    static GateAssessment decided(GateDecision decision) {
        return new Decided(decision, null);
    }
}
