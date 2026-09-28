package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.patterns.graph.NodeType;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The atomic unit of the workflow trajectory trace.
 * <p>
 * An explicit observation accepted by TraceRecorder implementations; not a durable checkpoint.
 *
 * @param workflowRunId unique identifier for this workflow execution
 * @param workflowName  the workflow name
 * @param fromStep      the previous node name (null for the first node)
 * @param toStep        the node that was just processed
 * @param timestamp     when this transition was recorded
 * @param stepDuration  wall-clock time the node took
 * @param tokensUsed    tokens consumed (0 for deterministic nodes)
 * @param costUsd       estimated USD cost (0.0 for deterministic nodes)
 * @param nodeType      whether the node was deterministic or agent-driven
 * @param label         routing label ("true"/"false" for gateway, option name for decision, null for sequential)
 * @param tracePath     absolute path to the step's trace file, or null if tracing is not enabled
 */
public record StepTransition(
        String workflowRunId,
        String workflowName,
        String fromStep,
        String toStep,
        Instant timestamp,
        Duration stepDuration,
        long tokensUsed,
        double costUsd,
        NodeType nodeType,
        String label,
        String tracePath
) {

    public StepTransition {
        Objects.requireNonNull(workflowRunId, "workflowRunId must not be null");
        Objects.requireNonNull(workflowName, "workflowName must not be null");
        Objects.requireNonNull(toStep, "toStep must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(stepDuration, "stepDuration must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
    }

    /** Backward-compat constructor without tracePath. */
    public StepTransition(
            String workflowRunId,
            String workflowName,
            String fromStep,
            String toStep,
            Instant timestamp,
            Duration stepDuration,
            long tokensUsed,
            double costUsd,
            NodeType nodeType,
            String label) {
        this(workflowRunId, workflowName, fromStep, toStep, timestamp, stepDuration,
                tokensUsed, costUsd, nodeType, label, null);
    }

    /** Backward-compat constructor without label or tracePath. */
    public StepTransition(
            String workflowRunId,
            String workflowName,
            String fromStep,
            String toStep,
            Instant timestamp,
            Duration stepDuration,
            long tokensUsed,
            double costUsd,
            NodeType nodeType) {
        this(workflowRunId, workflowName, fromStep, toStep, timestamp, stepDuration,
                tokensUsed, costUsd, nodeType, null, null);
    }
}
