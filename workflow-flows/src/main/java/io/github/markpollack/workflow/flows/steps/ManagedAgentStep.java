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

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.agents.AgentCreateParams;
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolset20260401Params;
import com.anthropic.models.beta.agents.BetaManagedAgentsModel;
import com.anthropic.models.beta.sessions.SessionCreateParams;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams;
import com.anthropic.models.beta.sessions.events.EventSendParams;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.AgentStep;
import io.github.markpollack.workflow.flows.Step;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;

/**
 * A {@link Step} that delegates to an Anthropic Managed Agent.
 * <p>
 * Creates a session per execution, streams events until the agent is idle or
 * terminated, and returns the collected text output. The workflow graph stays
 * in charge — this step makes Managed Agents just another execution substrate.
 *
 * <pre>{@code
 * // Reference a pre-created agent and environment
 * ManagedAgentStep step = ManagedAgentStep.of(agentId, environmentId)
 *     .name("remediation-agent")
 *     .timeout(Duration.ofMinutes(10));
 *
 * String result = step.execute(ctx, "Fix the failing test in AuthService.java");
 * }</pre>
 *
 * <h2>Workflow DSL usage</h2>
 * <pre>{@code
 * var workflow = WorkflowGraph.builder()
 *     .step("analyze", analyzeStep)
 *     .step("remediate", ManagedAgentStep.of(agentId, envId).name("fix"))
 *     .step("validate", validateStep)
 *     .build();
 * }</pre>
 *
 * @see <a href="https://docs.anthropic.com/en/api/overview">Anthropic API</a>
 */
public class ManagedAgentStep implements Step<String, String>, AgentStep {

	private static final Logger logger = LoggerFactory.getLogger(ManagedAgentStep.class);

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

	private final String name;

	private final SessionRunner runner;

	private final Duration timeout;

	/**
	 * Internal strategy for creating a session, streaming events, and returning text.
	 * Package-private for testability — tests inject a mock runner.
	 */
	@FunctionalInterface
	interface SessionRunner {

		String run(String input, Duration timeout) throws Exception;

	}

	ManagedAgentStep(String name, SessionRunner runner, Duration timeout) {
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.runner = Objects.requireNonNull(runner, "runner must not be null");
		this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
	}

	/**
	 * Creates a step that references a pre-existing Managed Agent and environment.
	 * <p>
	 * The agent and environment must already exist in the Anthropic API. A new session
	 * is created on each {@link #execute} call. The Anthropic client is created from
	 * the {@code ANTHROPIC_API_KEY} environment variable.
	 *
	 * @param agentId       the ID of the pre-created agent (e.g. {@code "agent_01ABC..."})
	 * @param environmentId the ID of the environment (e.g. {@code "env_01ABC..."})
	 * @return a new ManagedAgentStep
	 */
	public static ManagedAgentStep of(String agentId, String environmentId) {
		Objects.requireNonNull(agentId, "agentId must not be null");
		Objects.requireNonNull(environmentId, "environmentId must not be null");
		AnthropicClient client = AnthropicOkHttpClient.fromEnv();
		SessionRunner runner = createRunner(client, agentId, environmentId);
		return new ManagedAgentStep("managed-agent", runner, DEFAULT_TIMEOUT);
	}

	/**
	 * Creates a step that provisions a new Managed Agent with the given model and
	 * system prompt. The agent is created immediately and includes the default agent
	 * toolset (bash, read, write, edit, glob, grep, web_fetch, web_search).
	 * <p>
	 * The agent persists in the Anthropic API and is reused across executions. For
	 * agents with custom tool configurations, pre-create the agent via the SDK and
	 * use {@link #of(String, String)} instead.
	 *
	 * @param model         the model ID (e.g. {@code "claude-sonnet-4-6"})
	 * @param systemPrompt  the system prompt for the agent
	 * @param environmentId the ID of the environment to run sessions in
	 * @return a new ManagedAgentStep
	 */
	public static ManagedAgentStep create(String model, String systemPrompt, String environmentId) {
		Objects.requireNonNull(model, "model must not be null");
		Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
		Objects.requireNonNull(environmentId, "environmentId must not be null");
		AnthropicClient client = AnthropicOkHttpClient.fromEnv();
		var agent = client.beta().agents().create(AgentCreateParams.builder()
			.model(BetaManagedAgentsModel.of(model))
			.name("managed-agent")
			.system(systemPrompt)
			.addTool(BetaManagedAgentsAgentToolset20260401Params.builder()
				.type(BetaManagedAgentsAgentToolset20260401Params.Type.AGENT_TOOLSET_20260401)
				.build())
			.build());
		logger.info("Created managed agent '{}' (id={}, version={})", agent.name(), agent.id(),
				agent.version());
		SessionRunner runner = createRunner(client, agent.id(), environmentId);
		return new ManagedAgentStep(agent.name(), runner, DEFAULT_TIMEOUT);
	}

	/**
	 * Returns a copy of this step with a different name.
	 */
	public ManagedAgentStep name(String name) {
		return new ManagedAgentStep(name, this.runner, this.timeout);
	}

	/**
	 * Returns a copy of this step with a different timeout.
	 */
	public ManagedAgentStep timeout(Duration timeout) {
		return new ManagedAgentStep(this.name, this.runner, timeout);
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String execute(StepContext ctx, String input) {
		logger.debug("ManagedAgentStep '{}' sending message: {}", name,
				input != null && input.length() > 100 ? input.substring(0, 100) + "..." : input);
		try {
			String result = runner.run(input != null ? input : "", timeout);
			logger.debug("ManagedAgentStep '{}' received response: {}", name,
					result != null && result.length() > 100 ? result.substring(0, 100) + "..." : result);
			return result;
		}
		catch (Exception e) {
			throw new RuntimeException("ManagedAgentStep '" + name + "' failed", e);
		}
	}

	private static SessionRunner createRunner(AnthropicClient client, String agentId, String environmentId) {
		return (input, timeout) -> {
			var session = client.beta().sessions().create(SessionCreateParams.builder()
				.agent(agentId)
				.environmentId(environmentId)
				.build());
			logger.debug("Created session {} for agent {}", session.id(), agentId);

			try (var stream = client.beta().sessions().events().streamStreaming(session.id())) {
				// Send user message after stream is open (stream-first pattern)
				client.beta().sessions().events().send(session.id(), EventSendParams.builder()
					.addEvent(BetaManagedAgentsUserMessageEventParams.builder()
						.type(BetaManagedAgentsUserMessageEventParams.Type.USER_MESSAGE)
						.addTextContent(input)
						.build())
					.build());

				StringBuilder result = new StringBuilder();
				for (var event : (Iterable<BetaManagedAgentsStreamSessionEvents>) stream.stream()::iterator) {
					if (event.isAgentMessage()) {
						for (var block : event.asAgentMessage().content()) {
							result.append(block.text());
						}
					}
					else if (event.isSessionStatusIdle()) {
						break;
					}
					else if (event.isSessionStatusTerminated()) {
						logger.warn("Session {} terminated unexpectedly", session.id());
						break;
					}
					else if (event.isSessionError()) {
						throw new RuntimeException(
								"Session error in managed agent (session=" + session.id() + ")");
					}
				}
				return result.toString();
			}
		};
	}

}
