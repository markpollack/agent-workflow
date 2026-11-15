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

import io.github.markpollack.workflow.core.NodeType;
import io.github.markpollack.workflow.patterns.LoopPattern;
import io.github.markpollack.workflow.core.LoopResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A node declaration in a {@link Blueprint}.
 * <p>
 * Captures the node name, type, optional description, and the underlying
 * {@link GraphNode} implementation. Created via the static factory methods
 * rather than the constructor directly.
 *
 * @param name      unique node name within the blueprint
 * @param nodeType  whether this node is deterministic or agent-driven
 * @param description human-readable summary (may be empty)
 * @param graphNode the executable node implementation
 */
public record BlueprintNode(String name, NodeType nodeType, String description, GraphNode<?, ?> graphNode) {

    public BlueprintNode {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(nodeType, "nodeType must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(graphNode, "graphNode must not be null");
    }

    /**
     * Creates a deterministic node from a pure function.
     *
     * @param name the node name
     * @param fn   the function to execute
     * @param <I>  input type
     * @param <O>  output type
     * @return a DETERMINISTIC BlueprintNode
     */
    public static <I, O> BlueprintNode deterministic(String name, BiFunction<GraphContext, I, O> fn) {
        return new BlueprintNode(name, NodeType.DETERMINISTIC, "", GraphNode.of(name, fn));
    }

    /**
     * Creates a deterministic node with a description.
     *
     * @param name        the node name
     * @param description human-readable description
     * @param fn          the function to execute
     * @param <I>         input type
     * @param <O>         output type
     * @return a DETERMINISTIC BlueprintNode
     */
    public static <I, O> BlueprintNode deterministic(String name, String description, BiFunction<GraphContext, I, O> fn) {
        return new BlueprintNode(name, NodeType.DETERMINISTIC, description, GraphNode.of(name, fn));
    }

    /**
     * Creates an agent-typed node from a plain function.
     * <p>
     * Use when the "agent" is an external call (REST API, AgentClient, subprocess)
     * rather than an embedded {@link LoopPattern}. The node reports {@link NodeType#AGENT}
     * so it appears correctly in metrics and visualizations, but token counts will be
     * zero (the external call handles its own accounting).
     *
     * @param name        the node name
     * @param description human-readable purpose
     * @param fn          the function to execute
     * @param <I>         input type
     * @param <O>         output type
     * @return an AGENT BlueprintNode backed by a plain function
     */
    public static <I, O> BlueprintNode agentFunction(String name, String description,
            BiFunction<GraphContext, I, O> fn) {
        // Anonymous GraphNode that returns AGENT from nodeType() but delegates to a function
        GraphNode<I, O> agentNode = new GraphNode<>() {
            @Override public String name() { return name; }
            @Override public O execute(GraphContext context, I input) { return fn.apply(context, input); }
            @Override public NodeType nodeType() { return NodeType.AGENT; }
            @Override public String toString() { return "AgentFunctionNode[" + name + "]"; }
        };
        return new BlueprintNode(name, NodeType.AGENT, description, agentNode);
    }

    /**
     * Creates an agent-typed node from a plain function (no description).
     *
     * @param name the node name
     * @param fn   the function to execute
     * @param <I>  input type
     * @param <O>  output type
     * @return an AGENT BlueprintNode backed by a plain function
     */
    public static <I, O> BlueprintNode agentFunction(String name, BiFunction<GraphContext, I, O> fn) {
        return agentFunction(name, "", fn);
    }

    /**
     * Creates an agent node that wraps an {@link LoopPattern}.
     *
     * @param name       the node name
     * @param loop       the loop to execute
     * @param chatClient the ChatClient for the loop
     * @param tools      the tools available to the loop
     * @param <R>        the loop result type
     * @return an AGENT BlueprintNode
     */
    public static <R extends LoopResult> BlueprintNode agent(
            String name,
            LoopPattern<R> loop,
            ChatClient chatClient,
            List<ToolCallback> tools) {
        return new BlueprintNode(name, NodeType.AGENT, "", GraphNode.fromLoop(name, loop, chatClient, tools));
    }

    /**
     * Creates an agent node with a description.
     *
     * @param name        the node name
     * @param description human-readable description
     * @param loop        the loop to execute
     * @param chatClient  the ChatClient for the loop
     * @param tools       the tools available to the loop
     * @param <R>         the loop result type
     * @return an AGENT BlueprintNode
     */
    public static <R extends LoopResult> BlueprintNode agent(
            String name,
            String description,
            LoopPattern<R> loop,
            ChatClient chatClient,
            List<ToolCallback> tools) {
        return new BlueprintNode(name, NodeType.AGENT, description, GraphNode.fromLoop(name, loop, chatClient, tools));
    }
}
