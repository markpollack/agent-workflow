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

import io.a2a.A2A;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.TaskEvent;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.TextPart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * A {@link Step} that invokes a remote agent via the A2A (Agent-to-Agent) protocol.
 * <p>
 * Discovers the remote agent's {@link AgentCard} from the given URL, sends a text
 * message, and returns the text response extracted from the task artifacts.
 *
 * <pre>{@code
 * A2AStep agent = A2AStep.of("http://localhost:8080");
 * String response = agent.execute(ctx, "analyze this code");
 * }</pre>
 *
 * <h2>Workflow DSL usage</h2> <pre>{@code
 * Workflow.define("delegator")
 *     .step(A2AStep.of("http://remote-agent:8080").name("reviewer"))
 *     .build();
 * }</pre>
 */
public class A2AStep implements Step<String, String>, AgentStep {

	private static final Logger logger = LoggerFactory.getLogger(A2AStep.class);

	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

	private final String name;

	private final MessageSender sender;

	private final Duration timeout;

	/**
	 * Internal strategy for sending a message and returning the text response.
	 * Package-private for testability — tests inject a mock sender.
	 */
	@FunctionalInterface
	interface MessageSender {

		String send(String text, Duration timeout) throws Exception;

	}

	A2AStep(String name, MessageSender sender, Duration timeout) {
		this.name = Objects.requireNonNull(name, "name must not be null");
		this.sender = Objects.requireNonNull(sender, "sender must not be null");
		this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
	}

	/**
	 * Creates an A2AStep that connects to a remote A2A agent at the given URL.
	 * <p>
	 * Discovers the agent's {@link AgentCard} immediately. The step name defaults to the
	 * agent's name from the card.
	 * @param url the base URL of the remote A2A agent
	 * @return a new A2AStep
	 * @throws RuntimeException if the agent card cannot be resolved
	 */
	public static A2AStep of(String url) {
		Objects.requireNonNull(url, "url must not be null");
		try {
			String path = new URI(url).getPath();
			AgentCard card = A2A.getAgentCard(url, path + ".well-known/agent-card.json", null);
			String agentName = card.name() != null ? card.name() : "a2a-agent";
			MessageSender sender = createSender(card);
			return new A2AStep(agentName, sender, DEFAULT_TIMEOUT);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to discover A2A agent at " + url, e);
		}
	}

	/**
	 * Returns a copy of this step with a different name.
	 */
	public A2AStep name(String name) {
		return new A2AStep(name, this.sender, this.timeout);
	}

	/**
	 * Returns a copy of this step with a different timeout.
	 */
	public A2AStep timeout(Duration timeout) {
		return new A2AStep(this.name, this.sender, timeout);
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String execute(StepContext ctx, String input) {
		logger.debug("A2AStep '{}' sending message: {}", name,
				input != null && input.length() > 100 ? input.substring(0, 100) + "..." : input);
		try {
			String result = sender.send(input != null ? input : "", timeout);
			logger.debug("A2AStep '{}' received response: {}", name,
					result != null && result.length() > 100 ? result.substring(0, 100) + "..." : result);
			return result;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("A2AStep '" + name + "' interrupted", e);
		}
		catch (Exception e) {
			throw new RuntimeException("A2AStep '" + name + "' failed", e);
		}
	}

	private static MessageSender createSender(AgentCard card) {
		return (text, timeout) -> {
			Message message = new Message.Builder().role(Message.Role.USER)
				.parts(List.of(new TextPart(text, null)))
				.build();

			CompletableFuture<String> responseFuture = new CompletableFuture<>();
			AtomicReference<String> responseText = new AtomicReference<>("");

			BiConsumer<ClientEvent, AgentCard> consumer = (event, agentCard) -> {
				if (event instanceof TaskEvent taskEvent) {
					io.a2a.spec.Task completedTask = taskEvent.getTask();
					if (completedTask.getArtifacts() != null) {
						StringBuilder sb = new StringBuilder();
						for (Artifact artifact : completedTask.getArtifacts()) {
							if (artifact.parts() != null) {
								for (Part<?> part : artifact.parts()) {
									if (part instanceof TextPart textPart) {
										sb.append(textPart.getText());
									}
								}
							}
						}
						responseText.set(sb.toString());
					}
					responseFuture.complete(responseText.get());
				}
			};

			ClientConfig clientConfig = new ClientConfig.Builder().setAcceptedOutputModes(List.of("text")).build();
			Client client = Client.builder(card)
				.clientConfig(clientConfig)
				.withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
				.addConsumers(List.of(consumer))
				.build();

			client.sendMessage(message);
			return responseFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		};
	}

}
