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
import java.util.function.BiFunction;

/**
 * A node in a graph strategy representing a unit of work.
 * <p>
 * GraphNode is part of the composition layer and does NOT implement LoopPattern.
 * It can wrap either a simple function or an existing LoopPattern implementation.
 * <p>
 * Design:
 * <ul>
 *   <li>Nodes are identified by string names (enables late binding, serialization)</li>
 *   <li>Nodes execute with context and input, returning output</li>
 *   <li>Nodes can be coarse-grained (wrapping loops) or fine-grained (single operations)</li>
 * </ul>
 *
 * @param <I> the input type this node accepts
 * @param <O> the output type this node produces
 * @see FunctionGraphNode
 * @see LoopGraphNode
 */
public interface GraphNode<I, O> {

    /**
     * Returns the unique name of this node within the graph.
     * <p>
     * Names are used for edge definitions and must be unique within a GraphCompositionStrategy.
     *
     * @return the node name
     */
    String name();

    /**
     * Executes this node with the given context and input.
     *
     * @param context the execution context
     * @param input the input to process
     * @return the output of this node
     */
    O execute(GraphContext context, I input);

    /**
     * Returns the type of this node — deterministic or agent-driven.
     * <p>
     * Defaults to {@link NodeType#AGENT} for backward compatibility.
     * {@link FunctionGraphNode} overrides to {@link NodeType#DETERMINISTIC}.
     *
     * @return the node type
     */
    default NodeType nodeType() {
        return NodeType.AGENT;
    }

    /**
     * Creates a node from a simple function.
     * <p>
     * Use this for lightweight transformations or computations that don't require
     * an LLM call.
     *
     * @param name the node name
     * @param function the function to execute
     * @param <I> input type
     * @param <O> output type
     * @return a new GraphNode
     */
    static <I, O> GraphNode<I, O> of(String name, BiFunction<GraphContext, I, O> function) {
        return new FunctionGraphNode<>(name, function);
    }

    /**
     * Creates a node that wraps an existing LoopPattern.
     * <p>
     * The loop executes inside this node, allowing composition of loop patterns
     * within a graph structure. The input is used as the user message for the loop.
     *
     * @param name the node name
     * @param loop the LoopPattern to wrap
     * @param chatClient the ChatClient for the loop
     * @param tools the tools available to the loop
     * @param <R> the loop result type
     * @return a new GraphNode wrapping the loop
     */
    static <R extends LoopResult> GraphNode<String, R> fromLoop(
            String name,
            LoopPattern<R> loop,
            ChatClient chatClient,
            List<ToolCallback> tools) {
        return new LoopGraphNode<>(name, loop, chatClient, tools);
    }
}
