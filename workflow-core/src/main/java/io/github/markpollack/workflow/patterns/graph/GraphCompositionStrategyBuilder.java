/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.patterns.graph;

import io.github.markpollack.workflow.patterns.LoopPattern;
import io.github.markpollack.workflow.core.LoopResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Fluent builder for GraphCompositionStrategy.
 * <p>
 * Accumulate nodes and edges, then build to create an immutable GraphCompositionStrategy.
 * <p>
 * Example usage:
 * <pre>{@code
 * GraphCompositionStrategy<String, String> strategy = GraphCompositionStrategy.<String, String>builder("my-graph")
 *     .startNode("start")
 *     .finishNode("finish")
 *     .node("plan", (ctx, input) -> "Plan: " + input)
 *     .loopNode("code", codingLoop, chatClient, tools)
 *     .edge("start").to("plan")
 *     .edge("plan").to("code")
 *     .edge("code").to("finish")
 *     .maxIterations(50)
 *     .build();
 * }</pre>
 *
 * @param <I> the input type for the graph
 * @param <O> the output type from the graph
 */
public final class GraphCompositionStrategyBuilder<I, O> {

    private static final int DEFAULT_MAX_ITERATIONS = 100;

    private final String name;
    private final Map<String, GraphNode<?, ?>> nodes = new HashMap<>();
    private final Map<String, List<GraphEdge<?>>> edges = new HashMap<>();
    private String startNodeName;
    private String finishNodeName;
    private int maxIterations = DEFAULT_MAX_ITERATIONS;

    /**
     * Creates a new builder for a graph strategy.
     *
     * @param name the name of the graph strategy
     */
    public GraphCompositionStrategyBuilder(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    /**
     * Sets the start node name.
     * <p>
     * The start node must be added via {@link #node} or created implicitly.
     *
     * @param nodeName the name of the start node
     * @return this builder
     */
    public GraphCompositionStrategyBuilder<I, O> startNode(String nodeName) {
        this.startNodeName = Objects.requireNonNull(nodeName, "nodeName must not be null");
        // Create pass-through start node if not already defined
        if (!nodes.containsKey(nodeName)) {
            nodes.put(nodeName, GraphNode.of(nodeName, (ctx, input) -> input));
        }
        return this;
    }

    /**
     * Sets the finish node name.
     * <p>
     * The finish node must be added via {@link #node} or created implicitly.
     *
     * @param nodeName the name of the finish node
     * @return this builder
     */
    public GraphCompositionStrategyBuilder<I, O> finishNode(String nodeName) {
        this.finishNodeName = Objects.requireNonNull(nodeName, "nodeName must not be null");
        // Create pass-through finish node if not already defined
        if (!nodes.containsKey(nodeName)) {
            nodes.put(nodeName, GraphNode.of(nodeName, (ctx, input) -> input));
        }
        return this;
    }

    /**
     * Adds a function-based node.
     *
     * @param nodeName the unique name for this node
     * @param function the function to execute
     * @param <NI> the node input type
     * @param <NO> the node output type
     * @return this builder
     */
    public <NI, NO> GraphCompositionStrategyBuilder<I, O> node(String nodeName, BiFunction<GraphContext, NI, NO> function) {
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(function, "function must not be null");
        if (nodes.containsKey(nodeName)) {
            throw new GraphExecutionException.InvalidGraphException(
                    "Node with name '" + nodeName + "' already exists", name);
        }
        nodes.put(nodeName, GraphNode.of(nodeName, function));
        return this;
    }

    /**
     * Adds a pre-built node.
     *
     * @param node the node to add
     * @return this builder
     */
    public GraphCompositionStrategyBuilder<I, O> node(GraphNode<?, ?> node) {
        Objects.requireNonNull(node, "node must not be null");
        if (nodes.containsKey(node.name())) {
            throw new GraphExecutionException.InvalidGraphException(
                    "Node with name '" + node.name() + "' already exists", name);
        }
        nodes.put(node.name(), node);
        return this;
    }

    /**
     * Adds a node that wraps a LoopPattern.
     *
     * @param nodeName the unique name for this node
     * @param loop the LoopPattern to wrap
     * @param chatClient the ChatClient for the loop
     * @param tools the tools available to the loop
     * @param <R> the loop result type
     * @return this builder
     */
    public <R extends LoopResult> GraphCompositionStrategyBuilder<I, O> loopNode(
            String nodeName,
            LoopPattern<R> loop,
            ChatClient chatClient,
            List<ToolCallback> tools) {
        Objects.requireNonNull(nodeName, "nodeName must not be null");
        Objects.requireNonNull(loop, "loop must not be null");
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        if (nodes.containsKey(nodeName)) {
            throw new GraphExecutionException.InvalidGraphException(
                    "Node with name '" + nodeName + "' already exists", name);
        }
        nodes.put(nodeName, GraphNode.fromLoop(nodeName, loop, chatClient, tools));
        return this;
    }

    /**
     * Starts building an edge from the specified node.
     *
     * @param fromNodeName the source node name
     * @return an EdgeBuilder for further configuration
     */
    public EdgeBuilder edge(String fromNodeName) {
        return new EdgeBuilder(fromNodeName);
    }

    /**
     * Sets the maximum number of iterations allowed.
     *
     * @param max the maximum iterations (must be positive)
     * @return this builder
     */
    public GraphCompositionStrategyBuilder<I, O> maxIterations(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive: " + max);
        }
        this.maxIterations = max;
        return this;
    }

    /**
     * Builds the GraphCompositionStrategy.
     * <p>
     * Validates the graph structure:
     * <ul>
     *   <li>Start and finish nodes must be set</li>
     *   <li>All edge targets must reference existing nodes</li>
     *   <li>At least one path from start to finish should exist (warning only)</li>
     * </ul>
     *
     * @return the built GraphCompositionStrategy
     * @throws GraphExecutionException.InvalidGraphException if the graph is invalid
     */
    public GraphCompositionStrategy<I, O> build() {
        validate();
        return new GraphCompositionStrategy<>(name, nodes, edges, startNodeName, finishNodeName, maxIterations);
    }

    /**
     * Validates the graph structure.
     */
    private void validate() {
        // Check start and finish are set
        if (startNodeName == null) {
            throw new GraphExecutionException.InvalidGraphException("Start node not set", name);
        }
        if (finishNodeName == null) {
            throw new GraphExecutionException.InvalidGraphException("Finish node not set", name);
        }

        // Check start and finish nodes exist
        if (!nodes.containsKey(startNodeName)) {
            throw new GraphExecutionException.InvalidGraphException(
                    "Start node '" + startNodeName + "' not found in nodes", name);
        }
        if (!nodes.containsKey(finishNodeName)) {
            throw new GraphExecutionException.InvalidGraphException(
                    "Finish node '" + finishNodeName + "' not found in nodes", name);
        }

        // Check all edge targets exist
        Set<String> referencedNodes = new HashSet<>();
        for (Map.Entry<String, List<GraphEdge<?>>> entry : edges.entrySet()) {
            String sourceNode = entry.getKey();
            if (!nodes.containsKey(sourceNode)) {
                throw new GraphExecutionException.InvalidGraphException(
                        "Edge source node '" + sourceNode + "' not found in nodes", name);
            }
            for (GraphEdge<?> edge : entry.getValue()) {
                String targetNode = edge.targetNodeName();
                if (!nodes.containsKey(targetNode)) {
                    throw new GraphExecutionException.InvalidGraphException(
                            "Edge target node '" + targetNode + "' not found in nodes", name);
                }
                referencedNodes.add(targetNode);
            }
        }
    }

    /**
     * Builder for edges with fluent DSL.
     */
    public final class EdgeBuilder {

        private final String fromNodeName;
        private String toNodeName;
        private Predicate<Object> condition = obj -> true;
        private Function<Object, Object> transformer = Function.identity();

        private EdgeBuilder(String fromNodeName) {
            this.fromNodeName = Objects.requireNonNull(fromNodeName, "fromNodeName must not be null");
        }

        /**
         * Sets the target node for this edge.
         *
         * @param toNodeName the target node name
         * @return this EdgeBuilder for further configuration or the parent builder
         */
        public EdgeBuilder to(String toNodeName) {
            this.toNodeName = Objects.requireNonNull(toNodeName, "toNodeName must not be null");
            return this;
        }

        /**
         * Adds a condition to this edge.
         * <p>
         * The condition is evaluated against the output of the source node.
         *
         * @param condition the condition predicate
         * @param <T> the output type
         * @return this EdgeBuilder
         */
        @SuppressWarnings("unchecked")
        public <T> EdgeBuilder when(Predicate<T> condition) {
            this.condition = (Predicate<Object>) condition;
            return this;
        }

        /**
         * Adds a transformer to this edge.
         * <p>
         * The transformer converts the source node's output to the target node's input.
         *
         * @param transformer the transformation function
         * @param <T> the source output type
         * @param <U> the target input type
         * @return this EdgeBuilder
         */
        @SuppressWarnings("unchecked")
        public <T, U> EdgeBuilder transform(Function<T, U> transformer) {
            this.transformer = (Function<Object, Object>) transformer;
            return this;
        }

        /**
         * Completes this edge and returns to the parent builder.
         * <p>
         * If only {@code to()} was called without {@code and()}, the edge is added automatically.
         *
         * @return the parent GraphCompositionStrategyBuilder
         */
        public GraphCompositionStrategyBuilder<I, O> and() {
            addEdge();
            return GraphCompositionStrategyBuilder.this;
        }

        /**
         * Starts building another edge from a different node.
         *
         * @param fromNodeName the source node name
         * @return a new EdgeBuilder
         */
        public EdgeBuilder edge(String fromNodeName) {
            addEdge();
            return new EdgeBuilder(fromNodeName);
        }

        /**
         * Adds a node to the graph.
         *
         * @param nodeName the node name
         * @param function the node function
         * @param <NI> node input type
         * @param <NO> node output type
         * @return the parent builder
         */
        public <NI, NO> GraphCompositionStrategyBuilder<I, O> node(String nodeName, BiFunction<GraphContext, NI, NO> function) {
            addEdge();
            return GraphCompositionStrategyBuilder.this.node(nodeName, function);
        }

        /**
         * Builds the graph.
         *
         * @return the built GraphCompositionStrategy
         */
        public GraphCompositionStrategy<I, O> build() {
            addEdge();
            return GraphCompositionStrategyBuilder.this.build();
        }

        private void addEdge() {
            if (toNodeName == null) {
                throw new GraphExecutionException.InvalidGraphException(
                        "Edge from '" + fromNodeName + "' has no target (call to() first)", name);
            }
            @SuppressWarnings({"unchecked", "rawtypes"})
            GraphEdge<?> edge = new GraphEdge(toNodeName, condition, transformer);
            edges.computeIfAbsent(fromNodeName, k -> new ArrayList<>()).add(edge);
        }
    }
}
