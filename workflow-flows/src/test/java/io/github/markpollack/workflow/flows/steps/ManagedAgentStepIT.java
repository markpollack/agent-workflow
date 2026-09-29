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
import com.anthropic.models.beta.agents.BetaManagedAgentsModel;
import com.anthropic.models.beta.environments.BetaCloudConfigParams;
import com.anthropic.models.beta.environments.EnvironmentCreateParams;
import io.github.markpollack.workflow.flows.StepContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ManagedAgentStep} against the real Anthropic Managed Agents API.
 * <p>
 * Requires {@code ANTHROPIC_API_KEY} environment variable. Creates a temporary environment
 * and agent, runs a trivial prompt, and cleans up.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class ManagedAgentStepIT {

	private static AnthropicClient client;

	private static String environmentId;

	private static String agentId;

	@BeforeAll
	static void setUp() {
		client = AnthropicOkHttpClient.fromEnv();

		// Create a temporary environment
		var environment = client.beta().environments().create(EnvironmentCreateParams.builder()
			.name("managed-agent-step-it")
			.config(BetaCloudConfigParams.builder().build())
			.build());
		environmentId = environment.id();

		// Create a minimal agent (no tools — just a chatbot for fast response)
		var agent = client.beta().agents().create(AgentCreateParams.builder()
			.model(BetaManagedAgentsModel.CLAUDE_SONNET_4_6)
			.name("managed-agent-step-it")
			.system("You are a helpful assistant. Answer concisely in one sentence.")
			.build());
		agentId = agent.id();
	}

	@AfterAll
	static void tearDown() {
		if (client != null) {
			if (agentId != null) {
				client.beta().agents().archive(agentId);
			}
			if (environmentId != null) {
				client.beta().environments().archive(environmentId);
			}
			client.close();
		}
	}

	@Test
	void executeShouldReturnRealResponse() {
		ManagedAgentStep step = ManagedAgentStep.of(agentId, environmentId)
			.name("it-agent")
			.timeout(Duration.ofMinutes(3));

		String result = step.execute(new StepContext("run", "invocation", "attempt", 1, java.time.Instant.MAX), "What is 2+2? Reply with just the number.");

		assertThat(result).isNotBlank();
		assertThat(result).contains("4");
	}

}
