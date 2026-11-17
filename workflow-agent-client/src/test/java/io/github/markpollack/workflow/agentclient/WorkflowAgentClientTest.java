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
package io.github.markpollack.workflow.agentclient;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentClient.AgentClientRequestSpec;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.client.ApplicationCorrelation;
import io.github.markpollack.agents.model.AgentApi;
import io.github.markpollack.agents.model.AgentGeneration;
import io.github.markpollack.agents.model.AgentGenerationMetadata;
import io.github.markpollack.agents.model.AgentResponse;
import io.github.markpollack.agents.model.AgentResponseMetadata;
import io.github.markpollack.agents.model.AgentTaskRequest;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.steps.AgentClientStep;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowAgentClientTest {

	private static final StepContext STEP = new StepContext("run-1", "sha256:abc", "attempt-1", 2,
			StepContext.NO_DEADLINE);

	@Test
	void eachStepIsOneRunWithThePromptAsTheGoal() {
		var model = new Model("SUCCESS");
		try (AgentClient client = AgentClient.create(model)) {
			var adapter = WorkflowAgentClient.of(client);
			assertThat(adapter.execute("Create hello.txt", STEP)).isEqualTo("done: Create hello.txt");
			assertThat(model.requests).singleElement().satisfies(request -> assertThat(request.goal()).isEqualTo("Create hello.txt"));
		}
	}

	@Test
	void theResultHasNoTracePath() {
		try (AgentClient client = AgentClient.create(new Model("SUCCESS"))) {
			var result = WorkflowAgentClient.of(client).executeForResult("go", STEP);
			assertThat(result.text()).isEqualTo("done: go");
			assertThat(result.tracePath()).isNull();
		}
	}

	@Test
	void anUnsuccessfulRunFailsTheStepWithItsResponse() {
		var observed = new ArrayList<AgentClientResponse>();
		try (AgentClient client = AgentClient.create(new Model("ERROR"))) {
			var adapter = WorkflowAgentClient.builder(client)
				.onResponse((context, response) -> observed.add(response))
				.build();
			assertThatThrownBy(() -> adapter.execute("go", STEP)).isInstanceOf(WorkflowAgentClientException.class)
				.hasMessage("Agent Client run for Workflow run run-1, attempt 2 was not successful")
				.satisfies(failure -> assertThat(((WorkflowAgentClientException) failure).response())
					.isSameAs(observed.get(0)));
			assertThat(observed).singleElement().satisfies(response -> assertThat(response.isSuccessful()).isFalse());
		}
	}

	@Test
	void eachRunIsCorrelatedWithTheWorkflowStep() {
		var spec = new RecordingSpec();
		var observed = new ArrayList<StepContext>();
		var adapter = WorkflowAgentClient.builder(spec.client()).onResponse((context, response) -> observed.add(context)).build();

		adapter.execute("go", STEP);

		assertThat(spec.calls).containsExactly("goal:go", "correlation", "run");
		assertThat(spec.correlation).isEqualTo(new ApplicationCorrelation(Optional.empty(), Optional.empty(),
				Optional.empty(), Optional.of("run-1"), Optional.of("sha256:abc"), Optional.of("attempt-1")));
		assertThat(observed).containsExactly(STEP);
	}

	@Test
	void theRequestCustomizerRunsAfterTheGoalAndCorrelation() {
		var spec = new RecordingSpec();
		var adapter = WorkflowAgentClient.builder(spec.client())
			.request(request -> request.workingDirectory(Path.of("project")).executionTimeout(Duration.ofMinutes(5)))
			.build();

		adapter.executeForResult("go", STEP);

		assertThat(spec.calls).containsExactly("goal:go", "correlation", "workingDirectory", "executionTimeout", "run");
	}

	@Test
	void aCustomizerMustReturnASpec() {
		var adapter = WorkflowAgentClient.builder(new RecordingSpec().client()).request(request -> null).build();
		assertThatNullPointerException().isThrownBy(() -> adapter.execute("go", STEP))
			.withMessage("request customizer result");
	}

	@Test
	void itIsAWorkflowAgentForAgentClientStep() {
		try (AgentClient client = AgentClient.create(new Model("SUCCESS"))) {
			assertThat(AgentClientStep.of(WorkflowAgentClient.of(client), "Fix {input}")).isNotNull();
		}
	}

	@Test
	void argumentsAreRequired() {
		assertThatNullPointerException().isThrownBy(() -> WorkflowAgentClient.of(null)).withMessage("client");
		var builder = WorkflowAgentClient.builder(new RecordingSpec().client());
		assertThatNullPointerException().isThrownBy(() -> builder.request(null)).withMessage("request");
		assertThatNullPointerException().isThrownBy(() -> builder.onResponse(null)).withMessage("onResponse");
		var adapter = builder.build();
		assertThatNullPointerException().isThrownBy(() -> adapter.execute(null, STEP)).withMessage("prompt");
		assertThatNullPointerException().isThrownBy(() -> adapter.execute("go", null)).withMessage("context");
	}

	@Test
	void theCorrelationNamesNoExperiment() {
		var deadline = new StepContext("r", "i", "a", 1, Instant.parse("2030-01-01T00:00:00Z"));
		var correlation = WorkflowAgentClient.correlation(deadline);
		assertThat(correlation.experimentId()).isEmpty();
		assertThat(correlation.experimentRunId()).isEmpty();
		assertThat(correlation.experimentInvocationId()).isEmpty();
		assertThat(correlation.workflowRunId()).contains("r");
	}

	/** An agent that finishes every task with the given finish reason. */
	private static final class Model implements AgentApi {

		private final String finishReason;

		private final List<AgentTaskRequest> requests = new ArrayList<>();

		Model(String finishReason) {
			this.finishReason = finishReason;
		}

		@Override
		public AgentResponse call(AgentTaskRequest request) {
			this.requests.add(request);
			var generation = new AgentGeneration("done: " + request.goal(),
					new AgentGenerationMetadata(this.finishReason, Map.of()));
			return new AgentResponse(List.of(generation),
					new AgentResponseMetadata("fake", Duration.ZERO, "fake-session", Map.of()));
		}

	}

	/** An Agent Client whose request spec records the calls the adapter makes. */
	private static final class RecordingSpec {

		private final List<String> calls = new ArrayList<>();

		private ApplicationCorrelation correlation;

		AgentClient client() {
			AgentClientRequestSpec spec = (AgentClientRequestSpec) Proxy.newProxyInstance(
					getClass().getClassLoader(), new Class<?>[] { AgentClientRequestSpec.class },
					(proxy, method, args) -> {
						if (method.getName().equals("run")) {
							this.calls.add("run");
							var generation = new AgentGeneration("ok",
									new AgentGenerationMetadata("SUCCESS", Map.of()));
							return new AgentClientResponse(new AgentResponse(List.of(generation),
									new AgentResponseMetadata("fake", Duration.ZERO, "s", Map.of())));
						}
						if (method.getName().equals("correlation"))
							this.correlation = (ApplicationCorrelation) args[0];
						this.calls.add(method.getName());
						return proxy;
					});
			return (AgentClient) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { AgentClient.class }, (proxy, method, args) -> {
						if (method.getName().equals("goal") && args != null && args[0] instanceof String goal) {
							this.calls.add("goal:" + goal);
							return spec;
						}
						throw new UnsupportedOperationException(method.getName());
					});
		}

	}

}
