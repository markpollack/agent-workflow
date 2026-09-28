package io.github.markpollack.workflow.flows.workflow;

import io.github.markpollack.workflow.patterns.graph.NodeMetrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The compiled intermediate representation of a workflow.
 * <p>
 * Pure data structure — no execution logic, no Spring AI dependencies.
 * The compiler builds and verifies it; the
 * {@code TraceRecorder} reads it.
 *
 * @param name       the workflow identifier
 * @param nodes      ordered list of workflow nodes
 * @param edges      routing edges
 * @param startNode  the name of the first node to execute
 * @param finishNode the name of the final node
 * @param metrics    per-node execution metrics (empty until populated post-run)
 * @param <I>        the workflow input type
 * @param <O>        the workflow output type
 */
public record WorkflowGraph<I, O>(
        String name,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        String startNode,
        String finishNode,
        Map<String, NodeMetrics> metrics
) {

    public WorkflowGraph {
        Objects.requireNonNull(name, "name must not be null");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        Objects.requireNonNull(startNode, "startNode must not be null");
        Objects.requireNonNull(finishNode, "finishNode must not be null");
        metrics = Map.copyOf(metrics);
    }

    public static <I, O> WorkflowGraph<I, O> of(
            String name,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges,
            String startNode,
            String finishNode) {
        return new WorkflowGraph<>(name, nodes, edges, startNode, finishNode, Map.of());
    }

    public WorkflowGraph<I, O> withMetrics(Map<String, NodeMetrics> metrics) {
        return new WorkflowGraph<>(name, nodes, edges, startNode, finishNode, metrics);
    }

    // -- Lookup methods --

    /** Finds a node by name, or throws. */
    public WorkflowNode nodeByName(String nodeName) {
        return findNode(nodeName)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow '" + name + "': node '" + nodeName + "' not found"));
    }

    /** Finds a node by name. */
    public Optional<WorkflowNode> findNode(String nodeName) {
        return nodes.stream()
                .filter(n -> n.name().equals(nodeName))
                .findFirst();
    }

    /** Returns the edges originating from the given node. */
    public List<WorkflowEdge> edgesFrom(String nodeName) {
        return edges.stream()
                .filter(e -> e.from().equals(nodeName))
                .toList();
    }

    /** Returns the outgoing edges from a node (alias for edgesFrom). */
    public List<WorkflowEdge> outgoingEdges(String nodeName) {
        return edgesFrom(nodeName);
    }

    /** Finds the unconditional successor of a node, or null if none. */
    public String unconditionalSuccessor(String nodeName) {
        return edgesFrom(nodeName).stream()
                .filter(e -> e.condition() instanceof EdgeCondition.Unconditional)
                .map(WorkflowEdge::to)
                .findFirst()
                .orElse(null);
    }

    /** Finds the error edge matching an exception, or null if none. */
    public WorkflowEdge errorEdge(String nodeName, Exception ex) {
        for (WorkflowEdge edge : edgesFrom(nodeName)) {
            if (edge.condition() instanceof EdgeCondition.ErrorMatch em) {
                if (em.exType().isAssignableFrom(ex.getClass())) {
                    return edge;
                }
            }
        }
        return null;
    }

    /** Checks whether a direct edge exists between two nodes. */
    public boolean hasDirectEdge(String from, String to) {
        return edges.stream().anyMatch(e -> e.from().equals(from) && e.to().equals(to));
    }
}
