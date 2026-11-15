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
package io.github.markpollack.workflow.quarkus;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.Step;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Produces the durable workflow beans from {@code agent-workflow.*}. Each is a
 * {@link DefaultBean}, so an application bean of the same type replaces it, and a
 * prepackaged workflow can ship its {@link Step} beans in a JAR while an application
 * replaces just the one it needs.
 * <ul>
 * <li>{@link ExecutionPolicy} from {@code execution.maximum-attempts},
 * {@code maximum-depth}, {@code maximum-invocations} and {@code maximum-concurrency};
 * unset bounds keep {@link ExecutionPolicy#DEFAULT}.</li>
 * <li>{@link StepRegistry} holding every {@link Step} bean under its {@code @Named} name,
 * or its bean class name when it has none.</li>
 * <li>{@link ExecutionCompatibility} from {@code application-id}, {@code build-id} and
 * {@code configuration.*}.</li>
 * <li>{@link DurableWorkflows} over the local store at {@code database}, the H2 base file
 * path without its {@code .mv.db} suffix; closed at shutdown. Created when first
 * injected, and only then are {@code database}, {@code application-id} and
 * {@code build-id} required, in that order.</li>
 * </ul>
 */
@Singleton
public class WorkflowProducer {

	static final String PREFIX = "agent-workflow";

	private static final String CONFIGURATION = PREFIX + ".configuration.";

	/**
	 * Creates the execution bounds.
	 * @param attempts attempts per step
	 * @param depth nesting depth
	 * @param invocations step invocations per run
	 * @param concurrency concurrently executing steps
	 * @return the policy
	 */
	@Produces
	@Singleton
	@DefaultBean
	public ExecutionPolicy executionPolicy(
			@ConfigProperty(name = PREFIX + ".execution.maximum-attempts") Optional<Integer> attempts,
			@ConfigProperty(name = PREFIX + ".execution.maximum-depth") Optional<Integer> depth,
			@ConfigProperty(name = PREFIX + ".execution.maximum-invocations") Optional<Long> invocations,
			@ConfigProperty(name = PREFIX + ".execution.maximum-concurrency") Optional<Integer> concurrency) {
		ExecutionPolicy defaults = ExecutionPolicy.DEFAULT;
		return new ExecutionPolicy(attempts.orElse(defaults.maximumAttempts()),
				depth.orElse(defaults.maximumDepth()), invocations.orElse(defaults.maximumInvocations()),
				concurrency.orElse(defaults.maximumConcurrency()));
	}

	/**
	 * Registers every {@link Step} bean.
	 * @param steps the step beans
	 * @return the registry
	 */
	@Produces
	@Singleton
	@DefaultBean
	public StepRegistry stepRegistry(@Any Instance<Step<?, ?>> steps) {
		StepRegistry.Builder registry = StepRegistry.builder();
		for (Instance.Handle<Step<?, ?>> handle : steps.handles()) {
			String name = handle.getBean().getName();
			registry.register((name != null) ? name : handle.getBean().getBeanClass().getName(), handle.get());
		}
		return registry.build();
	}

	/**
	 * Creates the declared compatibility facts.
	 * @param applicationId the application namespace
	 * @param buildId the immutable application build identifier
	 * @param config the configuration, for {@code configuration.*}
	 * @return the compatibility facts
	 */
	@Produces
	@Singleton
	@DefaultBean
	public ExecutionCompatibility executionCompatibility(
			@ConfigProperty(name = PREFIX + ".application-id") Optional<String> applicationId,
			@ConfigProperty(name = PREFIX + ".build-id") Optional<String> buildId, Config config) {
		Map<String, String> configuration = new TreeMap<>();
		for (String key : config.getPropertyNames()) {
			if (key.startsWith(CONFIGURATION)) {
				config.getOptionalValue(key, String.class)
					.ifPresent(value -> configuration.put(key.substring(CONFIGURATION.length()), value));
			}
		}
		return new ExecutionCompatibility(required(applicationId, "application-id"), required(buildId, "build-id"),
				configuration);
	}

	/**
	 * Opens the durable runtime.
	 * @param database the store's H2 base file path
	 * @param steps the step registry
	 * @param compatibility the compatibility facts, resolved once the database is known
	 * @param policy the execution bounds
	 * @return the runtime
	 */
	@Produces
	@Singleton
	@DefaultBean
	public DurableWorkflows durableWorkflows(@ConfigProperty(name = PREFIX + ".database") Optional<String> database,
			StepRegistry steps, Instance<ExecutionCompatibility> compatibility, ExecutionPolicy policy) {
		Path store = Path.of(database.filter(text -> !text.isBlank())
			.orElseThrow(() -> new IllegalArgumentException(PREFIX + ".database is required")));
		return DurableWorkflows.open(store, steps, compatibility.get(), policy);
	}

	/**
	 * Closes the durable runtime at shutdown.
	 * @param runtime the runtime
	 */
	public void close(@Disposes DurableWorkflows runtime) {
		runtime.close();
	}

	private static String required(Optional<String> value, String property) {
		return value.filter(text -> !text.isBlank())
			.orElseThrow(() -> new IllegalArgumentException(
					PREFIX + "." + property + " is required with " + PREFIX + ".database"));
	}

}
