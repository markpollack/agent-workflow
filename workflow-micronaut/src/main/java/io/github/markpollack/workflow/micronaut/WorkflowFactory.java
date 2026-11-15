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
package io.github.markpollack.workflow.micronaut;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.Step;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.value.PropertyResolver;
import jakarta.inject.Singleton;

/**
 * Creates the durable workflow beans from {@code agent-workflow.*}. Each is
 * {@link Secondary}, so an application bean of the same type is chosen instead, and a
 * prepackaged workflow can ship its {@link Step} beans in a JAR while an application
 * replaces just the one it needs.
 * <ul>
 * <li>{@link ExecutionPolicy} from {@code execution.maximum-attempts},
 * {@code maximum-depth}, {@code maximum-invocations} and {@code maximum-concurrency};
 * unset bounds keep {@link ExecutionPolicy#DEFAULT}.</li>
 * <li>{@link StepRegistry} holding every {@link Step} bean under its {@code @Named} name,
 * or its bean type's class name when it has none.</li>
 * <li>{@link ExecutionCompatibility} from {@code application-id}, {@code build-id} and
 * {@code configuration.*}, and {@link DurableWorkflows} over the local store at
 * {@code database}, the H2 base file path without its {@code .mv.db} suffix. Both exist
 * only when {@code database} is set; the runtime is closed with the context.</li>
 * </ul>
 */
@Factory
public class WorkflowFactory {

	static final String PREFIX = "agent-workflow";

	/**
	 * Creates the execution bounds.
	 * @param attempts attempts per step, or null for the default
	 * @param depth nesting depth, or null for the default
	 * @param invocations step invocations per run, or null for the default
	 * @param concurrency concurrently executing steps, or null for the default
	 * @return the policy
	 */
	@Singleton
	@Secondary
	public ExecutionPolicy executionPolicy(
			@Property(name = PREFIX + ".execution.maximum-attempts") @Nullable Integer attempts,
			@Property(name = PREFIX + ".execution.maximum-depth") @Nullable Integer depth,
			@Property(name = PREFIX + ".execution.maximum-invocations") @Nullable Long invocations,
			@Property(name = PREFIX + ".execution.maximum-concurrency") @Nullable Integer concurrency) {
		ExecutionPolicy defaults = ExecutionPolicy.DEFAULT;
		return new ExecutionPolicy((attempts != null) ? attempts : defaults.maximumAttempts(),
				(depth != null) ? depth : defaults.maximumDepth(),
				(invocations != null) ? invocations : defaults.maximumInvocations(),
				(concurrency != null) ? concurrency : defaults.maximumConcurrency());
	}

	/**
	 * Registers every {@link Step} bean.
	 * @param context the bean context
	 * @return the registry
	 */
	@Singleton
	@Secondary
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public StepRegistry stepRegistry(BeanContext context) {
		StepRegistry.Builder registry = StepRegistry.builder();
		for (BeanRegistration<Step> step : context.getBeanRegistrations(Step.class)) {
			String name = step.getBeanDefinition()
				.stringValue(AnnotationUtil.NAMED)
				.orElseGet(() -> step.getBeanDefinition().getBeanType().getName());
			registry.register(name, (Step<?, ?>) step.getBean());
		}
		return registry.build();
	}

	/**
	 * Creates the declared compatibility facts.
	 * @param applicationId the application namespace
	 * @param buildId the immutable application build identifier
	 * @param properties the properties, for {@code configuration.*}
	 * @return the compatibility facts
	 */
	@Singleton
	@Secondary
	@Requires(property = PREFIX + ".database")
	public ExecutionCompatibility executionCompatibility(
			@Property(name = PREFIX + ".application-id") @Nullable String applicationId,
			@Property(name = PREFIX + ".build-id") @Nullable String buildId, PropertyResolver properties) {
		Map<String, String> configuration = new TreeMap<>();
		properties.getProperties(PREFIX + ".configuration", StringConvention.RAW)
			.forEach((key, value) -> configuration.put(key, String.valueOf(value)));
		return new ExecutionCompatibility(required(applicationId, "application-id"), required(buildId, "build-id"),
				configuration);
	}

	/**
	 * Opens the durable runtime.
	 * @param database the store's H2 base file path
	 * @param steps the step registry
	 * @param compatibility the compatibility facts
	 * @param policy the execution bounds
	 * @return the runtime, closed with the context
	 */
	@Singleton
	@Secondary
	@Bean(preDestroy = "close")
	@Requires(property = PREFIX + ".database")
	public DurableWorkflows durableWorkflows(@Property(name = PREFIX + ".database") String database,
			StepRegistry steps, ExecutionCompatibility compatibility, ExecutionPolicy policy) {
		return DurableWorkflows.open(Path.of(database), steps, compatibility, policy);
	}

	private static String required(@Nullable String value, String property) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(PREFIX + "." + property + " is required with " + PREFIX + ".database");
		}
		return value;
	}

}
