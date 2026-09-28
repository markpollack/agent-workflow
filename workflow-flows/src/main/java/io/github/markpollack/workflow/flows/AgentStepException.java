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
 * Thrown when a {@link Step} fails during execution.
 * <p>
 * Used by step implementations such as
 * {@link io.github.markpollack.workflow.flows.steps.ClaudeStep} to retain the original
 * cause when adapting checked application APIs with a consistent exception type.
 */
public class AgentStepException extends RuntimeException {

	public AgentStepException(String message) {
		super(message);
	}

	public AgentStepException(String message, Throwable cause) {
		super(message, cause);
	}

}
