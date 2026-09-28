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
import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;

/**
 * A {@link Step} that makes a single structured LLM call via spring-ai
 * {@link ChatClient}.
 * <p>
 * One call, one response — no tool loop. This is the right step for classification,
 * summarization, formatting, and structured extraction where the LLM does not need
 * to call tools.
 *
 * <pre>{@code
 * // Plain text response:
 * ChatClientStep.of(chatClient, "summarize the following review: {input}")
 *
 * // Structured response (spring-ai entity mapping):
 * ChatClientStep.of(chatClient, "classify this text: {input}", Category.class)
 * }</pre>
 *
 * <h2>Template substitution</h2>
 * <p>
 * The prompt template may contain {@code {input}} which is replaced with the
 * step's input value before the LLM call.
 */
public class ChatClientStep implements Step<String, String>, AgentStep {

    private final ChatClient chatClient;
    private final String promptTemplate;

    private ChatClientStep(ChatClient chatClient, String promptTemplate) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
        this.promptTemplate = Objects.requireNonNull(promptTemplate, "promptTemplate must not be null");
    }

    /**
     * Creates a {@code ChatClientStep} that returns a plain text response.
     *
     * @param chatClient     the spring-ai ChatClient to use
     * @param promptTemplate the prompt template (may contain {@code {input}})
     * @return a new ChatClientStep
     */
    public static ChatClientStep of(ChatClient chatClient, String promptTemplate) {
        return new ChatClientStep(chatClient, promptTemplate);
    }

    /**
     * Creates a {@link Step} that returns a structured response mapped to
     * {@code responseType} via spring-ai entity mapping.
     *
     * @param chatClient     the spring-ai ChatClient to use
     * @param promptTemplate the prompt template (may contain {@code {input}})
     * @param responseType   the class to map the LLM response to
     * @param <T>            the structured response type
     * @return a Step that produces a typed entity
     */
    public static <T> Step<String, T> of(ChatClient chatClient, String promptTemplate, Class<T> responseType) {
        Objects.requireNonNull(chatClient, "chatClient must not be null");
        Objects.requireNonNull(promptTemplate, "promptTemplate must not be null");
        Objects.requireNonNull(responseType, "responseType must not be null");
        return (ctx, input) -> {
            String resolved = resolve(promptTemplate, input);
            return chatClient.prompt().user(resolved).call().entity(responseType);
        };
    }

    @Override
    public String name() {
        return "ChatClientStep";
    }

    @Override
    public String execute(StepContext ctx, String input) {
        String resolved = resolve(promptTemplate, input);
        return chatClient.prompt().user(resolved).call().content();
    }

    private static String resolve(String template, String input) {
        return template.replace("{input}", input != null ? input : "");
    }
}
