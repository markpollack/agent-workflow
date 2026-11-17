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

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentClient.AgentClientRequestSpec;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.client.ApplicationCorrelation;
import io.github.markpollack.workflow.flows.StepContext;

/**
 * Agent Client as a Workflow agent: pass it to {@code AgentClientStep} and each step
 * invocation becomes one synchronous Agent Client {@code run()}.
 *
 * <pre>{@code
 * try (AgentClient client = AgentClient.builder(provider).build()) {
 *     var step = AgentClientStep.of(WorkflowAgentClient.of(client), "Fix the failing test in {input}");
 *     var workflow = Workflows.define("fix").then(step).terminate(SUCCEEDED).build();
 *     ...
 * }
 * }</pre>
 *
 * <p>
 * Each run carries the step's Workflow run, invocation and attempt ids as its
 * {@link ApplicationCorrelation}, so a recorder such as Agent Journal's records which
 * Workflow step an agent execution belongs to. A run that is not successful fails the
 * step with a {@link WorkflowAgentClientException}, which carries the response, so
 * Workflow's retry and failure handling apply.
 *
 * <p>
 * The Workflow runtime enforces the step's deadline. To bound the agent run itself, set
 * an Agent Client execution timeout or other request options with
 * {@link Builder#request(UnaryOperator)}.
 *
 * <p>
 * This module is the only part of Agent Workflow that depends on Agent Client. Agent
 * Client never depends on Agent Workflow.
 */
public final class WorkflowAgentClient implements io.github.markpollack.workflow.flows.steps.AgentClient {

	private final AgentClient client;

	private final UnaryOperator<AgentClientRequestSpec> request;

	private final BiConsumer<StepContext, AgentClientResponse> onResponse;

	private WorkflowAgentClient(Builder builder) {
		this.client = builder.client;
		this.request = builder.request;
		this.onResponse = builder.onResponse;
	}

	/**
	 * An adapter that runs each prompt with the client's defaults.
	 * @param client the Agent Client to run each step with; the caller keeps and closes
	 * it
	 * @return the adapter
	 */
	public static WorkflowAgentClient of(AgentClient client) {
		return builder(client).build();
	}

	/**
	 * A builder for an adapter with request options or a response observer.
	 * @param client the Agent Client to run each step with; the caller keeps and closes
	 * it
	 * @return the builder
	 */
	public static Builder builder(AgentClient client) {
		return new Builder(client);
	}

	/**
	 * Runs the prompt and returns the run's result text.
	 * @param prompt the resolved prompt, used as the goal
	 * @param context the step's run, invocation and attempt
	 * @return the run's result text
	 * @throws WorkflowAgentClientException if the run is not successful
	 */
	@Override
	public String execute(String prompt, StepContext context) {
		return executeForResult(prompt, context).text();
	}

	/**
	 * Runs the prompt as one Agent Client {@code run()} correlated with the step.
	 * @param prompt the resolved prompt, used as the goal
	 * @param context the step's run, invocation and attempt
	 * @return the run's result text; the trace path is always {@code null}, since a
	 * recorder keeps the execution record
	 * @throws WorkflowAgentClientException if the run is not successful
	 */
	@Override
	public ExecutionResult executeForResult(String prompt, StepContext context) {
		Objects.requireNonNull(prompt, "prompt");
		Objects.requireNonNull(context, "context");
		AgentClientRequestSpec spec = this.client.goal(prompt).correlation(correlation(context));
		AgentClientResponse response = Objects.requireNonNull(this.request.apply(spec), "request customizer result")
			.run();
		this.onResponse.accept(context, response);
		if (!response.isSuccessful()) {
			throw new WorkflowAgentClientException("Agent Client run for Workflow run " + context.runId()
					+ ", attempt " + context.attemptNumber() + " was not successful", response);
		}
		return new ExecutionResult(response.getResult(), null);
	}

	/**
	 * The correlation each run carries: the step's Workflow run, invocation and attempt.
	 * @param context the step context
	 * @return the correlation
	 */
	static ApplicationCorrelation correlation(StepContext context) {
		return new ApplicationCorrelation(Optional.empty(), Optional.empty(), Optional.empty(),
				Optional.of(context.runId()), Optional.of(context.invocationId()), Optional.of(context.attemptId()));
	}

	/** Configures a {@link WorkflowAgentClient}. */
	public static final class Builder {

		private final AgentClient client;

		private UnaryOperator<AgentClientRequestSpec> request = UnaryOperator.identity();

		private BiConsumer<StepContext, AgentClientResponse> onResponse = (context, response) -> {
		};

		private Builder(AgentClient client) {
			this.client = Objects.requireNonNull(client, "client");
		}

		/**
		 * Adjusts every request before it runs, for example to set an execution timeout
		 * or a working directory. The goal and correlation are already set.
		 * @param request returns the spec to run
		 * @return this builder
		 */
		public Builder request(UnaryOperator<AgentClientRequestSpec> request) {
			this.request = Objects.requireNonNull(request, "request");
			return this;
		}

		/**
		 * Observes every response, successful or not, before the step completes or
		 * fails.
		 * @param onResponse receives the step context and the response
		 * @return this builder
		 */
		public Builder onResponse(BiConsumer<StepContext, AgentClientResponse> onResponse) {
			this.onResponse = Objects.requireNonNull(onResponse, "onResponse");
			return this;
		}

		/**
		 * Builds the adapter.
		 * @return the adapter
		 */
		public WorkflowAgentClient build() {
			return new WorkflowAgentClient(this);
		}

	}

}
