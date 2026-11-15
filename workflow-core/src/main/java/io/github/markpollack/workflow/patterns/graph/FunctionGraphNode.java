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
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * A graph node that executes a simple function.
 * <p>
 * Use this for lightweight transformations, computations, or routing logic
 * that doesn't require an LLM call.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
public final class FunctionGraphNode<I, O> implements GraphNode<I, O> {

    private final String name;
    private final BiFunction<GraphContext, I, O> function;

    /**
     * Creates a new function-based graph node.
     *
     * @param name the node name (must be unique within graph)
     * @param function the function to execute
     */
    public FunctionGraphNode(String name, BiFunction<GraphContext, I, O> function) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.function = Objects.requireNonNull(function, "function must not be null");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public O execute(GraphContext context, I input) {
        return function.apply(context, input);
    }

    @Override
    public NodeType nodeType() {
        return NodeType.DETERMINISTIC;
    }

    @Override
    public String toString() {
        return "FunctionGraphNode[" + name + "]";
    }
}
