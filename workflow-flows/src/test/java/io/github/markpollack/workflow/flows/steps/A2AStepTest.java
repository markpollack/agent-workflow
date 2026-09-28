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

class A2AStepTest {

	private final StepContext ctx = new StepContext("run", "invocation", "attempt", 1, java.time.Instant.MAX,
			java.util.Map.of());

	@Test
	void executeShouldDelegateToSenderAndReturnResponse() {
		A2AStep step = new A2AStep("test-agent", (text, timeout) -> "response: " + text, Duration.ofSeconds(10));

		String result = step.execute(ctx, "hello");

		assertThat(result).isEqualTo("response: hello");
	}

	@Test
	void nameShouldReturnConfiguredName() {
		A2AStep step = new A2AStep("my-agent", (text, timeout) -> "", Duration.ofSeconds(10));

		assertThat(step.name()).isEqualTo("my-agent");
	}

	@Test
	void nameOverrideShouldReturnNewInstance() {
		A2AStep original = new A2AStep("original", (text, timeout) -> "ok", Duration.ofSeconds(10));
		A2AStep renamed = original.name("renamed");

		assertThat(renamed.name()).isEqualTo("renamed");
		assertThat(original.name()).isEqualTo("original");
		// Sender is preserved — same behavior
		assertThat(renamed.execute(ctx, "test")).isEqualTo("ok");
	}

	@Test
	void timeoutOverrideShouldReturnNewInstance() {
		A2AStep original = new A2AStep("agent", (text, timeout) -> {
			assertThat(timeout).isEqualTo(Duration.ofSeconds(30));
			return "ok";
		}, Duration.ofSeconds(10));

		A2AStep withTimeout = original.timeout(Duration.ofSeconds(30));
		assertThat(withTimeout.execute(ctx, "test")).isEqualTo("ok");
	}

	@Test
	void executeShouldWrapSenderException() {
		A2AStep step = new A2AStep("failing-agent", (text, timeout) -> {
			throw new TimeoutException("connection timed out");
		}, Duration.ofSeconds(10));

		assertThatThrownBy(() -> step.execute(ctx, "hello")).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("A2AStep 'failing-agent' failed")
			.hasCauseInstanceOf(TimeoutException.class);
	}

	@Test
	void executeShouldHandleNullInput() {
		A2AStep step = new A2AStep("agent", (text, timeout) -> "got: " + text, Duration.ofSeconds(10));

		String result = step.execute(ctx, null);

		assertThat(result).isEqualTo("got: ");
	}

	@Test
	void shouldImplementAgentStepMarker() {
		A2AStep step = new A2AStep("agent", (text, timeout) -> "", Duration.ofSeconds(10));

		assertThat(step).isInstanceOf(AgentStep.class);
	}

	@Test
	void interruptedWaitPreservesOriginalCauseAndRestoresFlag() {
		var original = new java.util.concurrent.atomic.AtomicReference<InterruptedException>();
		var step = new A2AStep("interrupted", (text, timeout) -> {
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
