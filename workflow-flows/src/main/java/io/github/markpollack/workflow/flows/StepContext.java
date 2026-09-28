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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable execution metadata, not a channel for business results or service lookup. The
 * runtime creates this after charging an attempt and supplies it to Step.execute. A
 * recovered attempt retains its run and invocation IDs and receives a new attempt ID. A
 * repeated authored placement is a distinct logical invocation; another physical attempt
 * after a crash is not. The run ID can correlate observations with external systems
 * without making their recording IDs checkpoint authority.
 * <p>
 * Configuration is a defensive immutable copy of declared deployment configuration. It is
 * not a service container or a result channel. The deadline is an absolute saved bound,
 * not a timer or a live cancellation token. Store transitions enforce eligibility; this
 * object does not interrupt or terminate application code.
 *
 * @param runId stable workflow run correlation, distinct from an external journal
 * recording ID
 * @param invocationId stable logical step invocation
 * @param attemptId unique physical attempt
 * @param attemptNumber one-based charged attempt number
 * @param deadline persisted absolute run deadline
 * @param configuration declared application configuration, copied at registration
 */
public record StepContext(String runId, String invocationId, String attemptId, int attemptNumber, Instant deadline,
		Map<String, String> configuration) {
	/**
	 * Create immutable metadata for a charged attempt. IDs/deadline/configuration must be
	 * non-null and the attempt number positive; identity provenance is established by the
	 * runtime, not by constructing this value.
	 */
	public StepContext {
		Objects.requireNonNull(runId);
		Objects.requireNonNull(invocationId);
		Objects.requireNonNull(attemptId);
		Objects.requireNonNull(deadline);
		if (attemptNumber < 1)
			throw new IllegalArgumentException("positive attempt number required");
		configuration = Map.copyOf(configuration);
	}
}
