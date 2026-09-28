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
package io.github.markpollack.workflow.flows.steps;

import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.AgentStep;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedAgentStepTest {

	private final StepContext ctx = new StepContext("run", "invocation", "attempt", 1, java.time.Instant.MAX,
			java.util.Map.of());

	@Test
	void executeShouldDelegateToRunnerAndReturnResponse() {
		ManagedAgentStep step = new ManagedAgentStep("test-agent", (input, timeout) -> "response: " + input,
				Duration.ofMinutes(5));

		String result = step.execute(ctx, "hello");

		assertThat(result).isEqualTo("response: hello");
	}

	@Test
	void nameShouldReturnConfiguredName() {
		ManagedAgentStep step = new ManagedAgentStep("my-agent", (input, timeout) -> "", Duration.ofMinutes(5));

		assertThat(step.name()).isEqualTo("my-agent");
	}

	@Test
	void nameOverrideShouldReturnNewInstance() {
		ManagedAgentStep original = new ManagedAgentStep("original", (input, timeout) -> "ok", Duration.ofMinutes(5));
		ManagedAgentStep renamed = original.name("renamed");

		assertThat(renamed.name()).isEqualTo("renamed");
		assertThat(original.name()).isEqualTo("original");
		assertThat(renamed.execute(ctx, "test")).isEqualTo("ok");
	}

	@Test
	void timeoutOverrideShouldReturnNewInstance() {
		ManagedAgentStep original = new ManagedAgentStep("agent", (input, timeout) -> {
			assertThat(timeout).isEqualTo(Duration.ofMinutes(10));
			return "ok";
		}, Duration.ofMinutes(5));

		ManagedAgentStep withTimeout = original.timeout(Duration.ofMinutes(10));
		assertThat(withTimeout.execute(ctx, "test")).isEqualTo("ok");
	}

	@Test
	void executeShouldWrapRunnerException() {
		ManagedAgentStep step = new ManagedAgentStep("failing-agent", (input, timeout) -> {
			throw new TimeoutException("session timed out");
		}, Duration.ofMinutes(5));

		assertThatThrownBy(() -> step.execute(ctx, "hello")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("ManagedAgentStep 'failing-agent' failed")
			.hasCauseInstanceOf(TimeoutException.class);
	}

	@Test
	void executeShouldHandleNullInput() {
		ManagedAgentStep step = new ManagedAgentStep("agent", (input, timeout) -> "got: " + input,
				Duration.ofMinutes(5));

		String result = step.execute(ctx, null);

		assertThat(result).isEqualTo("got: ");
	}

	@Test
	void shouldImplementAgentStepMarker() {
		ManagedAgentStep step = new ManagedAgentStep("agent", (input, timeout) -> "", Duration.ofMinutes(5));

		assertThat(step).isInstanceOf(AgentStep.class);
	}

	@Test
	void interruptedWaitPreservesOriginalCauseAndRestoresFlag() {
		var original = new java.util.concurrent.atomic.AtomicReference<InterruptedException>();
		var step = new ManagedAgentStep("interrupted", (text, timeout) -> {
			Thread.currentThread().interrupt();
			try {
				new java.util.concurrent.CountDownLatch(1).await();
				throw new AssertionError("unreachable");
			}
			catch (InterruptedException failure) {
				assertThat(Thread.currentThread().isInterrupted()).isFalse();
				original.set(failure);
				throw failure;
			}
		}, Duration.ofSeconds(10));
		try {
			assertThatThrownBy(() -> step.execute(ctx, "hello")).isInstanceOf(RuntimeException.class)
				.satisfies(failure -> assertThat(failure.getCause()).isSameAs(original.get()).isNotNull());
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

}
