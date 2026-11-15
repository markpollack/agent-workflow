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

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.Step;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Provides the durable workflow beans from {@link WorkflowProperties}. Every bean backs
 * off when the application declares its own, so a prepackaged workflow can ship its
 * {@code Step} beans in a JAR and an application replaces just the one it needs.
 * <ul>
 * <li>{@link ExecutionPolicy} from {@code agent-workflow.execution.*}.</li>
 * <li>{@link StepRegistry} holding every {@link Step} bean under its bean name.</li>
 * <li>{@link ExecutionCompatibility} and {@link DurableWorkflows}, when
 * {@code agent-workflow.database} is set. The runtime is closed with the context.</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(DurableWorkflows.class)
@EnableConfigurationProperties(WorkflowProperties.class)
public class WorkflowAutoConfiguration {

	/**
	 * The execution bounds.
	 * @param properties the workflow properties
	 * @return the policy
	 */
	@Bean
	@ConditionalOnMissingBean
	public ExecutionPolicy workflowExecutionPolicy(WorkflowProperties properties) {
		return properties.getExecution().toPolicy();
	}

	/**
	 * Every {@link Step} bean, registered under its bean name.
	 * @param beans the bean factory
	 * @return the registry
	 */
	@Bean
	@ConditionalOnMissingBean
	public StepRegistry workflowStepRegistry(ListableBeanFactory beans) {
		@SuppressWarnings({ "rawtypes", "unchecked" })
		Map<String, Step<?, ?>> steps = (Map) beans.getBeansOfType(Step.class);
		return StepRegistry.of(new TreeMap<>(steps));
	}

	/**
	 * The declared compatibility facts.
	 * @param properties the workflow properties
	 * @return the compatibility facts
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = WorkflowProperties.PREFIX, name = "database")
	public ExecutionCompatibility workflowExecutionCompatibility(WorkflowProperties properties) {
		return new ExecutionCompatibility(required(properties.getApplicationId(), "application-id"),
				required(properties.getBuildId(), "build-id"), properties.getConfiguration());
	}

	/**
	 * The durable runtime over the local store at {@code agent-workflow.database}.
	 * @param properties the workflow properties
	 * @param steps the step registry
	 * @param compatibility the compatibility facts
	 * @param policy the execution bounds
	 * @return the runtime, closed with the context
	 */
	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnProperty(prefix = WorkflowProperties.PREFIX, name = "database")
	public DurableWorkflows durableWorkflows(WorkflowProperties properties, StepRegistry steps,
			ExecutionCompatibility compatibility, ExecutionPolicy policy) {
		return DurableWorkflows.open(Path.of(properties.getDatabase()), steps, compatibility, policy);
	}

	private static String required(String value, String property) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(WorkflowProperties.PREFIX + "." + property + " is required with "
					+ WorkflowProperties.PREFIX + ".database");
		}
		return value;
	}

}
