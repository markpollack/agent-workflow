package io.github.markpollack.workflow.flows.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowGraphTest {


    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    @Test
    void shouldBuildGraphWithThreeNodesAndTwoEdges() {
        WorkflowNode fetchNode = new WorkflowNode.StepNode("fetch", String.class, String.class);
        WorkflowNode analyzeNode = new WorkflowNode.StepNode("analyze", String.class, String.class);
        WorkflowNode reportNode = new WorkflowNode.StepNode("report", String.class, String.class);

        WorkflowEdge edge1 = WorkflowEdge.of("fetch", "analyze");
        WorkflowEdge edge2 = WorkflowEdge.of("analyze", "report");

        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "pr-review",
                List.of(fetchNode, analyzeNode, reportNode),
                List.of(edge1, edge2),
                "fetch",
                "report"
        );

        assertThat(graph.name()).isEqualTo("pr-review");
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.startNode()).isEqualTo("fetch");
        assertThat(graph.finishNode()).isEqualTo("report");
    }

    @Test
    void nodesShouldBeImmutable() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test", List.of(new WorkflowNode.StepNode("a", String.class, String.class)),
                List.of(), "a", "a"
        );

        assertThat(graph.nodes()).isUnmodifiable();
    }

    @Test
    void edgesShouldBeImmutable() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(new WorkflowNode.StepNode("a", String.class, String.class)),
                List.of(WorkflowEdge.of("a", "a")),
                "a", "a"
        );

        assertThat(graph.edges()).isUnmodifiable();
    }

    // -------------------------------------------------------------------------
    // findNode / edgesFrom / nodeByName
    // -------------------------------------------------------------------------

    @Test
    void findNodeShouldReturnNodeByName() {
        WorkflowNode node = new WorkflowNode.StepNode("analyze", String.class, String.class);
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test", List.of(node), List.of(), "analyze", "analyze"
        );

        assertThat(graph.findNode("analyze")).contains(node);
        assertThat(graph.findNode("missing")).isEmpty();
    }

    @Test
    void edgesFromShouldReturnOutgoingEdges() {
        WorkflowEdge e1 = WorkflowEdge.of("a", "b");
        WorkflowEdge e2 = WorkflowEdge.of("a", "c");
        WorkflowEdge e3 = WorkflowEdge.of("b", "c");

        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(
                        new WorkflowNode.StepNode("a", String.class, String.class),
                        new WorkflowNode.StepNode("b", String.class, String.class),
                        new WorkflowNode.StepNode("c", String.class, String.class)
                ),
                List.of(e1, e2, e3),
                "a", "c"
        );

        assertThat(graph.edgesFrom("a")).containsExactly(e1, e2);
        assertThat(graph.edgesFrom("b")).containsExactly(e3);
        assertThat(graph.edgesFrom("c")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // WorkflowNode (sealed interface)
    // -------------------------------------------------------------------------

    @Test void nodeContainsFullContractsWithoutAnExecutable() {
        var node = new WorkflowNode.StepNode("fetch",String.class,String.class);
        assertThat(node.input()).isEqualTo(String.class);
        assertThat(node.output()).isEqualTo(String.class);
    }

    // -------------------------------------------------------------------------
    // WorkflowEdge (EdgeCondition-based)
    // -------------------------------------------------------------------------

    @Test
    void unconditionalEdgeShouldBeUnconditional() {
        WorkflowEdge edge = WorkflowEdge.of("a", "b");

        assertThat(edge.isUnconditional()).isTrue();
        assertThat(edge.condition()).isInstanceOf(EdgeCondition.Unconditional.class);
        assertThat(edge.label()).isNull();
    }

    @Test
    void conditionalEdgeShouldCarryCondition() {
        WorkflowEdge edge = WorkflowEdge.conditional("a", "b",
                new EdgeCondition.BooleanGuard(true), "true");

        assertThat(edge.isUnconditional()).isFalse();
        assertThat(edge.condition()).isEqualTo(new EdgeCondition.BooleanGuard(true));
        assertThat(edge.label()).isEqualTo("true");
    }

    @Test
    void labeledEdgeShouldCarryLabel() {
        WorkflowEdge edge = WorkflowEdge.labeled("a", "b", "on-success");

        assertThat(edge.label()).isEqualTo("on-success");
        assertThat(edge.isUnconditional()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Lookup methods
    // -------------------------------------------------------------------------

    @Test
    void unconditionalSuccessorShouldFindSequentialEdge() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(new WorkflowNode.StepNode("a", String.class, String.class),
                        new WorkflowNode.StepNode("b", String.class, String.class)),
                List.of(WorkflowEdge.sequence("a", "b")),
                "a", "b"
        );

        assertThat(graph.unconditionalSuccessor("a")).isEqualTo("b");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> graph.unconditionalSuccessor("b")).hasMessageContaining("one unconditional successor");
    }

    @Test
    void hasDirectEdgeShouldReturnCorrectly() {
        WorkflowGraph<String, String> graph = WorkflowGraph.of(
                "test",
                List.of(new WorkflowNode.StepNode("a", String.class, String.class),
                        new WorkflowNode.StepNode("b", String.class, String.class)),
                List.of(WorkflowEdge.sequence("a", "b")),
                "a", "b"
        );

        assertThat(graph.hasDirectEdge("a", "b")).isTrue();
        assertThat(graph.hasDirectEdge("b", "a")).isFalse();
    }

}
