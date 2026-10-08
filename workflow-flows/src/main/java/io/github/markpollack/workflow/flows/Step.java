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
 * An application-supplied unit of work with explicit business input and output. The
 * durable runtime selects its input from the validated workflow, saves progress and
 * invokes the registered object outside store transactions, on the caller for
 * sequential execution or a bounded JDK worker for concurrent execution. Dependencies belong in constructors; business results belong in the
 * returned value, not in {@link StepContext}.
 * <p>
 * For durable registration, a concrete class must declare resolvable types, for example
 * {@code implements Step<Request, Reply>}. Concrete inherited generic declarations are
 * also supported. Reflection reads those class signatures; it cannot recover erased type
 * arguments from raw implementations, unresolved generic instances or ordinary lambda
 * classes. Such registrations refuse validation rather than guessing Object.
 * <p>
 * The same supplied instance may serve multiple runs. Its thread safety and dependency
 * lifecycle belong to the application. A crash before result acceptance can cause another
 * physical attempt of the same logical invocation; use its invocation ID for external
 * idempotency where available.
 *
 * @param <I> declared business input type, including concrete generic arguments
 * @param <O> declared business output type, including concrete generic arguments
 */
@FunctionalInterface
public interface Step<I, O> {

	/**
	 * Perform one physical attempt using the exact input selected for this invocation.
	 * Successful return is provisional until the runtime accepts the result; cancellation
	 * or deadline expiry can reject a late result without interrupting this call.
	 * <p>
	 * Report application failures through unchecked exceptions. When adapting checked
	 * APIs, retain the original cause (for example in UncheckedIOException). When
	 * catching InterruptedException, restore the thread's interrupt flag before wrapping
	 * it. The runtime defers an observed interrupt until persistence and guard cleanup
	 * finish. A normally returning interrupted step can commit its result, but resume
	 * does not enter its successor. An interrupted failure remains an application
	 * failure.
	 * @param context immutable run/invocation/attempt metadata, not business state
	 * @param input typed effective input reconstructed from saved values
	 * @return a value satisfying the declared output and durable codec contract
	 * @throws RuntimeException when application work cannot complete; the runtime records
	 * a terminal step failure rather than automatically retrying a known failure
	 */
	O execute(StepContext context, I input);

	/**
	 * Human-readable label for inspection. Registration and authored placement, rather
	 * than this label, determine durable invocation identity.
	 * @return a display name, defaulting to the implementation's simple class name
	 */
	default String name() {
		return getClass().getSimpleName();
	}

}
