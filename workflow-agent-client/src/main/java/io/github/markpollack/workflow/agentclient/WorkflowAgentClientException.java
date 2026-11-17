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

import java.io.Serial;

import io.github.markpollack.agents.client.AgentClientResponse;

/** A Workflow step's Agent Client run that was not successful; it fails the step. */
public final class WorkflowAgentClientException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	private final transient AgentClientResponse response;

	WorkflowAgentClientException(String message, AgentClientResponse response) {
		super(message);
		this.response = response;
	}

	/**
	 * The unsuccessful response.
	 * @return the response, or {@code null} after deserialization
	 */
	public AgentClientResponse response() {
		return this.response;
	}

}
