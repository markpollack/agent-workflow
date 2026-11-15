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
package io.github.markpollack.workflow.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code agent-workflow.*} properties.
 * <ul>
 * <li>{@code database}: the H2 base file path of the local run store, without its
 * {@code .mv.db} suffix. Setting it creates the {@code DurableWorkflows} bean.</li>
 * <li>{@code application-id}, {@code build-id} and {@code configuration.*}: the declared
 * compatibility facts a run is admitted and recovered with; both identifiers are
 * required with a database.</li>
 * <li>{@code execution.*}: the execution bounds; unset bounds keep
 * {@link ExecutionPolicy#DEFAULT}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = WorkflowProperties.PREFIX)
public class WorkflowProperties {

	/** The property prefix. */
	public static final String PREFIX = "agent-workflow";

	private String database;

	private String applicationId;

	private String buildId;

	private Map<String, String> configuration = new LinkedHashMap<>();

	private final Execution execution = new Execution();

	/** @return the store's H2 base file path, or null for none */
	public String getDatabase() {
		return this.database;
	}

	/** @param database the store's H2 base file path */
	public void setDatabase(String database) {
		this.database = database;
	}

	/** @return the application namespace recorded with each run */
	public String getApplicationId() {
		return this.applicationId;
	}

	/** @param applicationId the application namespace recorded with each run */
	public void setApplicationId(String applicationId) {
		this.applicationId = applicationId;
	}

	/** @return the immutable application build identifier recorded with each run */
	public String getBuildId() {
		return this.buildId;
	}

	/** @param buildId the immutable application build identifier recorded with each run */
	public void setBuildId(String buildId) {
		this.buildId = buildId;
	}

	/** @return the declared settings whose digest a run must match to be recovered */
	public Map<String, String> getConfiguration() {
		return this.configuration;
	}

	/** @param configuration the declared settings whose digest a run must match */
	public void setConfiguration(Map<String, String> configuration) {
		this.configuration = configuration;
	}

	/** @return the execution bounds */
	public Execution getExecution() {
		return this.execution;
	}

	/** Execution bounds; each unset bound keeps {@link ExecutionPolicy#DEFAULT}. */
	public static class Execution {

		private Integer maximumAttempts;

		private Integer maximumDepth;

		private Long maximumInvocations;

		private Integer maximumConcurrency;

		/** @return attempts per step, or null for the default */
		public Integer getMaximumAttempts() {
			return this.maximumAttempts;
		}

		/** @param maximumAttempts attempts per step */
		public void setMaximumAttempts(Integer maximumAttempts) {
			this.maximumAttempts = maximumAttempts;
		}

		/** @return nesting depth, or null for the default */
		public Integer getMaximumDepth() {
			return this.maximumDepth;
		}

		/** @param maximumDepth nesting depth */
		public void setMaximumDepth(Integer maximumDepth) {
			this.maximumDepth = maximumDepth;
		}

		/** @return step invocations per run, or null for the default */
		public Long getMaximumInvocations() {
			return this.maximumInvocations;
		}

		/** @param maximumInvocations step invocations per run */
		public void setMaximumInvocations(Long maximumInvocations) {
			this.maximumInvocations = maximumInvocations;
		}

		/** @return concurrently executing steps, or null for the default */
		public Integer getMaximumConcurrency() {
			return this.maximumConcurrency;
		}

		/** @param maximumConcurrency concurrently executing steps */
		public void setMaximumConcurrency(Integer maximumConcurrency) {
			this.maximumConcurrency = maximumConcurrency;
		}

		ExecutionPolicy toPolicy() {
			ExecutionPolicy defaults = ExecutionPolicy.DEFAULT;
			return new ExecutionPolicy(
					(this.maximumAttempts != null) ? this.maximumAttempts : defaults.maximumAttempts(),
					(this.maximumDepth != null) ? this.maximumDepth : defaults.maximumDepth(),
					(this.maximumInvocations != null) ? this.maximumInvocations : defaults.maximumInvocations(),
					(this.maximumConcurrency != null) ? this.maximumConcurrency : defaults.maximumConcurrency());
		}

	}

}
