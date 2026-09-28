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
import io.github.markpollack.workflow.flows.Step;

import java.util.Objects;

/**
 * A {@link Step} that delegates to an {@link AgentClient}.
 * <p>
 * Use when the agent invocation goes through the {@link AgentClient} abstraction
 * rather than directly through a specific client. This is the right step when:
 * <ul>
 *   <li>The client is injected (e.g., from a Spring context)</li>
 *   <li>You need to swap implementations for testing with a mock agent</li>
 *   <li>The underlying agent backend is not Claude-specific</li>
 * </ul>
 *
 * <pre>{@code
 * AgentClientStep.of(agentClient, "analyze this PR: {input}")
 * }</pre>
 *
 * <h2>Template substitution</h2>
 * <p>
 * The prompt template may contain {@code {input}} which is replaced with the
 * step's input value before delegation to the client.
 */
public class AgentClientStep implements Step<String, String>, AgentStep {

    private final AgentClient client;
    private final String promptTemplate;

    private AgentClientStep(AgentClient client, String promptTemplate) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate must not be null");
    }

    /**
     * Creates an {@code AgentClientStep} with the given client and prompt template.
     *
     * @param client         the agent client to delegate to
     * @param promptTemplate the prompt template (may contain {@code {input}})
     * @return a new AgentClientStep
     */
    public static AgentClientStep of(AgentClient client, String promptTemplate) {
        return new AgentClientStep(client, promptTemplate);
    }

    @Override
    public String name() {
        return "AgentClientStep";
    }

    @Override
    public String execute(StepContext ctx, String input) {
        String resolved = promptTemplate.replace("{input}", input != null ? input : "");
        AgentClient.ExecutionResult result = client.executeForResult(resolved, ctx);
        return result.text();
    }

}
