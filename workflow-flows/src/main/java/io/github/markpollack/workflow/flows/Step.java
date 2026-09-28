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
package io.github.markpollack.workflow.flows;

/**
 * An application-supplied unit of typed work. The runtime supplies execution metadata,
 * saves exact inputs and results, and invokes this object outside persistence transactions.
 * Implementations declare concrete input/output types; dependencies belong in constructors.
 *
 * @param <I> input type
 * @param <O> output type
 */
@FunctionalInterface
public interface Step<I, O> {
    /** Execute on the runtime caller's thread and return the business result. */
    O execute(StepContext context, I input) throws Exception;

    /** Human-readable name; invocation identity is assigned by the compiled placement. */
    default String name() { return getClass().getSimpleName(); }

    /** Graph inspection hint. Runtime admission validates the concrete generic declaration. */
    default Class<?> inputType() { return Object.class; }

    /** Graph inspection hint. This does not bypass generic type validation. */
    default Class<?> outputType() { return Object.class; }
}
