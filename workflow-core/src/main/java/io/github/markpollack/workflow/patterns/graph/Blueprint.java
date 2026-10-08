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

import io.github.markpollack.workflow.core.LoopPattern;
import io.github.markpollack.workflow.core.LoopResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A named, inspectable in-memory composition built from {@link BlueprintNode}s and
 * {@link BlueprintEdge}s. This companion API executes through the legacy graph
 * strategy; it does not provide the typed Workflows DSL or local durable recovery.
 * <p>
 * {@code Blueprint} is a higher-level API over {@link GraphCompositionStrategy}. It
 * separates the declaration of <em>what</em> a pipeline does (node names, types,
 * descriptions) from the execution mechanics of the graph engine.
 * <p>
 * Typical usage — linear pipeline:
 * <pre>{@code
 * Blueprint<String, String> pipeline = Blueprint.<String, String>builder("coverage-pipeline")
 *     .deterministic("measure-baseline", "Run JaCoCo to get initial coverage",
 *         (ctx, input) -> measureCoverage(input))
 *     .agent("write-tests", "Generate test cases to improve coverage",
 *         writeTestsLoop, chatClient, tools)
 *     .deterministic("compile-and-test", "Verify tests compile and pass",
 *         (ctx, input) -> runTests(input))
 *     .sequential()   // auto-wire linear edges
 *     .build();
 *
 * GraphResult<String> result = pipeline.execute("src/main/java");
 * }</pre>
 *
 * For pipelines that require conditional routing, add explicit {@link BlueprintEdge}s
 * before calling {@code build()} instead of (or in addition to) {@code sequential()}.
 *
 * @param <I> the input type accepted by the first node
 * @param <O> the output type produced by the last node
 */
public final class Blueprint<I, O> {

    private final String name;
    private final GraphCompositionStrategy<I, O> strategy;
    private final List<BlueprintNode> nodes;

    private Blueprint(String name, GraphCompositionStrategy<I, O> strategy, List<BlueprintNode> nodes) {
        this.name = name;
        this.strategy = strategy;
        this.nodes = nodes;
    }

    /**
     * The name of this blueprint.
     *
     * @return blueprint name
     */
    public String name() {
        return name;
    }

    /**
     * The ordered list of node declarations in this blueprint.
     * <p>
     * Suitable for visualization, cost estimation, and documentation.
     *
     * @return immutable list of blueprint nodes
     */
    public List<BlueprintNode> nodes() {
        return nodes;
    }

    /**
     * Executes the blueprint with the given input.
     *
     * @param input the input to the first node
     * @return the result of graph execution
     */
    public GraphResult<O> execute(I input) {
        return strategy.execute(input);
    }

    /**
     * Returns the underlying {@link GraphCompositionStrategy} for advanced usage.
     *
     * @return the graph composition strategy
     */
    public GraphCompositionStrategy<I, O> toGraphCompositionStrategy() {
        return strategy;
    }

    /**
     * Creates a new {@link BlueprintBuilder}.
     *
     * @param name the blueprint name
     * @param <I>  the input type
     * @param <O>  the output type
     * @return a new builder
     */
    public static <I, O> BlueprintBuilder<I, O> builder(String name) {
        return new BlueprintBuilder<>(name);
    }

    @Override
    public String toString() {
        return "Blueprint[" + name + ", nodes=" + nodes.stream().map(BlueprintNode::name).toList() + "]";
    }

    /**
     * Fluent builder for {@link Blueprint}.
     *
     * @param <I> the input type of the first node
     * @param <O> the output type of the last node
     */
    public static final class BlueprintBuilder<I, O> {

        private static final int DEFAULT_MAX_ITERATIONS = 100;

        private final String name;
        private final List<BlueprintNode> nodes = new ArrayList<>();
        private final List<BlueprintEdge> edges = new ArrayList<>();
        private int maxIterations = DEFAULT_MAX_ITERATIONS;

        private BlueprintBuilder(String name) {
            this.name = Objects.requireNonNull(name, "name must not be null");
        }

        /**
         * Adds a deterministic node.
         *
         * @param nodeName the unique node name
         * @param fn       the function to execute
         * @param <NI>     node input type
         * @param <NO>     node output type
         * @return this builder
         */
        public <NI, NO> BlueprintBuilder<I, O> deterministic(String nodeName, BiFunction<GraphContext, NI, NO> fn) {
            nodes.add(BlueprintNode.deterministic(nodeName, fn));
            return this;
        }

        /**
         * Adds a deterministic node with a description.
         *
         * @param nodeName    the unique node name
         * @param description human-readable purpose of this node
         * @param fn          the function to execute
         * @param <NI>        node input type
         * @param <NO>        node output type
         * @return this builder
         */
        public <NI, NO> BlueprintBuilder<I, O> deterministic(String nodeName, String description,
                BiFunction<GraphContext, NI, NO> fn) {
            nodes.add(BlueprintNode.deterministic(nodeName, description, fn));
            return this;
        }

        /**
         * Adds an agent-typed node backed by a plain function.
         * <p>
         * Use when the agent call is external (REST, AgentClient, subprocess) rather
         * than an embedded {@link io.github.markpollack.workflow.core.LoopPattern}.
         * The node reports {@link NodeType#AGENT} in metrics and visualization.
         *
         * @param nodeName    the unique node name
         * @param description human-readable purpose of this node
         * @param fn          the function to execute
         * @param <NI>        node input type
         * @param <NO>        node output type
         * @return this builder
         */
        public <NI, NO> BlueprintBuilder<I, O> agentFunction(String nodeName, String description,
                BiFunction<GraphContext, NI, NO> fn) {
            nodes.add(BlueprintNode.agentFunction(nodeName, description, fn));
            return this;
        }

        /**
         * Adds an agent-typed node backed by a plain function (no description).
         *
         * @param nodeName the unique node name
         * @param fn       the function to execute
         * @param <NI>     node input type
         * @param <NO>     node output type
         * @return this builder
         */
        public <NI, NO> BlueprintBuilder<I, O> agentFunction(String nodeName,
                BiFunction<GraphContext, NI, NO> fn) {
            nodes.add(BlueprintNode.agentFunction(nodeName, fn));
            return this;
        }

        /**
         * Adds an agent node.
         *
         * @param nodeName   the unique node name
         * @param loop       the LoopPattern to execute
         * @param chatClient the ChatClient for the loop
         * @param tools      the tools available to the loop
         * @param <R>        the loop result type
         * @return this builder
         */
        public <R extends LoopResult> BlueprintBuilder<I, O> agent(
                String nodeName,
                LoopPattern<R> loop,
                ChatClient chatClient,
                List<ToolCallback> tools) {
            nodes.add(BlueprintNode.agent(nodeName, loop, chatClient, tools));
            return this;
        }

        /**
         * Adds an agent node with a description.
         *
         * @param nodeName    the unique node name
         * @param description human-readable purpose of this node
         * @param loop        the LoopPattern to execute
         * @param chatClient  the ChatClient for the loop
         * @param tools       the tools available to the loop
         * @param <R>         the loop result type
         * @return this builder
         */
        public <R extends LoopResult> BlueprintBuilder<I, O> agent(
                String nodeName,
                String description,
                LoopPattern<R> loop,
                ChatClient chatClient,
                List<ToolCallback> tools) {
            nodes.add(BlueprintNode.agent(nodeName, description, loop, chatClient, tools));
            return this;
        }

        /**
         * Adds an explicit edge to the blueprint.
         * <p>
         * Use for conditional routing or type transformation between nodes.
         * {@link BlueprintEdge} provides factory methods for common cases.
         *
         * @param edge the edge to add
         * @return this builder
         */
        public BlueprintBuilder<I, O> edge(BlueprintEdge edge) {
            edges.add(Objects.requireNonNull(edge, "edge must not be null"));
            return this;
        }

        /**
         * Auto-wires unconditional sequential edges between nodes in declaration order.
         * <p>
         * Calling this after declaring all nodes is the simplest way to create a
         * linear pipeline. May be called alongside explicit {@link #edge(BlueprintEdge)}
         * calls for mixed topologies, in which case explicit edges are evaluated first
         * (first-match semantics).
         *
         * @return this builder
         */
        public BlueprintBuilder<I, O> sequential() {
            for (int i = 0; i < nodes.size() - 1; i++) {
                edges.add(BlueprintEdge.of(nodes.get(i).name(), nodes.get(i + 1).name()));
            }
            return this;
        }

        /**
         * Sets the maximum graph iterations (default 100).
         *
         * @param max must be positive
         * @return this builder
         */
        public BlueprintBuilder<I, O> maxIterations(int max) {
            this.maxIterations = max;
            return this;
        }

        /**
         * Builds the {@link Blueprint}.
         * <p>
         * Converts node and edge declarations into a {@link GraphCompositionStrategy}
         * and wraps it in a {@code Blueprint}.
         *
         * @return the built Blueprint
         * @throws IllegalStateException if no nodes have been declared
         */
        public Blueprint<I, O> build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("Blueprint '" + name + "' has no nodes");
            }

            GraphCompositionStrategyBuilder<I, O> graphBuilder = GraphCompositionStrategy.builder(name);

            // Add all nodes first so startNode/finishNode don't create pass-through duplicates
            for (BlueprintNode node : nodes) {
                graphBuilder.node(node.graphNode());
            }

            // Set start/finish after nodes exist in the builder
            graphBuilder
                    .startNode(nodes.get(0).name())
                    .finishNode(nodes.get(nodes.size() - 1).name())
                    .maxIterations(maxIterations);

            // Register edges
            for (BlueprintEdge edge : edges) {
                graphBuilder.edge(edge.from())
                        .to(edge.to())
                        .when(edge.condition())
                        .transform(edge.transformer())
                        .and();
            }

            return new Blueprint<>(name, graphBuilder.build(), List.copyOf(nodes));
        }
    }
}
