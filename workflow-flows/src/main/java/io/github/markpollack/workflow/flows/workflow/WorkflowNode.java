package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.core.AgentContext;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.patterns.graph.NodeType;

import java.util.function.Predicate;

/**
 * A node in the {@link WorkflowGraph} intermediate representation.
 * <p>
 * Sealed interface hierarchy — each variant carries only the fields it needs.
 * Java 21 pattern matching {@code switch} enforces exhaustiveness at compile time.
 */
public sealed interface WorkflowNode permits
        WorkflowNode.StepNode,
        WorkflowNode.GatewayNode,
        WorkflowNode.DecisionNode,
        WorkflowNode.GateNode,
        WorkflowNode.LoopEntryNode,
        WorkflowNode.LoopCheckNode,
        WorkflowNode.LoopExitNode,
        WorkflowNode.ForkNode,
        WorkflowNode.JoinNode {

    /** Unique node name within the graph. */
    String name();

    /** Cost category: DETERMINISTIC or AGENT. */
    NodeType type();

    // -- Convenience factory methods (backward-compat for tests) --

    static StepNode deterministic(String name, Step<?, ?> step) {
        return new StepNode(name, NodeType.DETERMINISTIC, step);
    }

    static StepNode agent(String name, Step<?, ?> step) {
        return new StepNode(name, NodeType.AGENT, step);
    }

    // -- Variants --

    /** A regular step node used by graph construction and analysis. */
    record StepNode(String name, NodeType type, Step<?, ?> step) implements WorkflowNode {}

    /** Deterministic routing — evaluates predicate, routes on BooleanGuard edges. Value passed unchanged. */
    record GatewayNode(String name, Predicate<Object> predicate, String joinNodeName) implements WorkflowNode {
        @Override public NodeType type() { return NodeType.DETERMINISTIC; }
    }

    /** LLM-driven routing — executes routingStep, routes on OptionMatch edges. */
    record DecisionNode(String name, Step<?, ?> routingStep, String joinNodeName) implements WorkflowNode {
        @Override public NodeType type() { return NodeType.AGENT; }
    }

    /** Quality/approval gate — evaluates Gate, routes on GateMatch edges. */
    record GateNode(String name, Gate<?> gate, String joinNodeName,
                    Step<?, ?> reflector, int maxRetries) implements WorkflowNode {
        @Override public NodeType type() { return NodeType.DETERMINISTIC; }
    }

    /** Loop entry (while-do) — evaluates exitCondition BEFORE body; follows LoopExit or LoopContinue. */
    record LoopEntryNode(String name, Predicate<AgentContext> exitCondition) implements WorkflowNode {
        @Override public NodeType type() { return NodeType.DETERMINISTIC; }
    }

    /** Loop check (do-while) — evaluates outputExitCondition AFTER body runs on currentValue. */
    record LoopCheckNode(String name, Predicate<Object> outputExitCondition) implements WorkflowNode {
        @Override public NodeType type() { return NodeType.DETERMINISTIC; }
    }

    /** Loop exit — passthrough; marks where execution resumes after loop. */
    record LoopExitNode(String name) implements WorkflowNode {
        @Override public NodeType type() { return NodeType.DETERMINISTIC; }
    }

    /** Parallel fan-out — dispatches one branch per BranchIndex edge concurrently. */
    record ForkNode(String name, String joinNodeName) implements WorkflowNode {
        @Override public NodeType type() { return NodeType.DETERMINISTIC; }
    }

    /**
     * How a parallel join combines branch results.
     * <ul>
     *   <li>{@code ENRICHMENT} — input passes through unchanged; each branch writes its output
     *       to context under {@code Steps.outputOf(stepName)}. Downstream reads by name.</li>
     *   <li>{@code COLLECTION} — branch outputs collected into {@code List<Object>} passed as
     *       the next step's input. Used for homogeneous fan-out where downstream aggregates.</li>
     * </ul>
     */
    enum JoinMode { ENRICHMENT, COLLECTION }

    /** Convergence point — join mode controls whether input passes through or results are collected. */
    record JoinNode(String name, JoinMode joinMode) implements WorkflowNode {
        @Override public NodeType type() { return NodeType.DETERMINISTIC; }
    }
}
