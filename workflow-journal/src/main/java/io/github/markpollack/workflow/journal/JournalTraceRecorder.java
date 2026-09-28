package io.github.markpollack.workflow.journal;

import io.github.markpollack.journal.Run;
import io.github.markpollack.workflow.flows.workflow.StepTransition;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;
import io.github.markpollack.workflow.patterns.graph.NodeType;

import java.util.List;
import java.util.Objects;

/**
 * A {@link TraceRecorder} that writes each step completion into a journal {@link Run}
 * as a typed {@link WorkflowStepEvent}.
 *
 * <h2>Metrics recorded per step</h2>
 * <ul>
 *   <li>{@code workflow.steps.total} — incremented after every step</li>
 *   <li>{@code workflow.steps.agent} — incremented for AGENT-type steps only</li>
 *   <li>{@code workflow.cost.usd} — accumulated cost (when available)</li>
 *   <li>{@code workflow.tokens.used} — accumulated tokens (when available)</li>
 *   <li>{@code workflow.duration.ms.<stepName>} — per-step wall-clock time</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>The application submits observations to the recorder explicitly. This recorder
 * is not connected to durable workflow execution and does not own checkpoints.
 *
 * <p>{@link #getTrace(String)} returns an empty list — the journal is the source of
 * truth. Use {@link TraceRecorder#inMemory()} when trace replay is also needed.
 */
public final class JournalTraceRecorder implements TraceRecorder {

    private final Run run;

    JournalTraceRecorder(Run run) {
        this.run = Objects.requireNonNull(run, "run must not be null");
    }

    @Override
    public void record(StepTransition transition) {
        run.logEvent(new WorkflowStepEvent(
                transition.timestamp(),
                transition.workflowRunId(),
                transition.workflowName(),
                transition.toStep(),
                transition.fromStep(),
                transition.nodeType(),
                transition.stepDuration(),
                transition.tokensUsed(),
                transition.costUsd(),
                transition.label(),
                transition.tracePath()
        ));

        run.logMetric("workflow.steps.total", 1);
        run.logMetric("workflow.duration.ms." + transition.toStep(),
                transition.stepDuration().toMillis());

        if (transition.nodeType() == NodeType.AGENT) {
            run.logMetric("workflow.steps.agent", 1);
            if (transition.costUsd() > 0) {
                run.logMetric("workflow.cost.usd", transition.costUsd());
            }
            if (transition.tokensUsed() > 0) {
                run.logMetric("workflow.tokens.used", transition.tokensUsed());
            }
        }
    }

    @Override
    public List<StepTransition> getTrace(String workflowRunId) {
        return List.of();
    }
}
