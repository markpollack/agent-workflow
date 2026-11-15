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

import java.util.List;
import java.util.Objects;

/**
 * A graph node that wraps an existing LoopPattern.
 * <p>
 * This enables composition of loop patterns within a graph structure.
 * The input to this node is used as the user message for the wrapped loop.
 * <p>
 * Design:
 * <ul>
 *   <li>Subgraphs/loops are nodes - composition, not inheritance</li>
 *   <li>Coarse-grained nodes can perform multiple LLM calls</li>
 *   <li>This allows flexible node granularity</li>
 * </ul>
 *
 * @param <R> the loop result type
 */
public final class LoopGraphNode<R extends LoopResult> implements GraphNode<String, R> {

    private final String name;
    private final LoopPattern<R> loop;
    private final ChatClient chatClient;
    private final List<ToolCallback> tools;

    /**
     * Creates a new loop-wrapping graph node.
     *
     * @param name the node name (must be unique within graph)
     * @param loop the LoopPattern to execute
     * @param chatClient the ChatClient for the loop
     * @param tools the tools available to the loop
     */
    public LoopGraphNode(
            String name,
            LoopPattern<R> loop,
            ChatClient chatClient,
            List<ToolCallback> tools) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.loop = Objects.requireNonNull(loop, "loop must not be null");
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public R execute(GraphContext context, String input) {
        return loop.execute(input, chatClient, tools);
    }

    /**
     * Returns the wrapped LoopPattern.
     *
     * @return the loop
     */
    public LoopPattern<R> loop() {
        return loop;
    }

    @Override
    public String toString() {
        return "LoopGraphNode[" + name + ", loop=" + loop.loopType() + "]";
    }
}
