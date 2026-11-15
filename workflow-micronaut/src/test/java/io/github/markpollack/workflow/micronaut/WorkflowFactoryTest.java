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
import java.util.HashMap;
import java.util.Map;

import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import io.github.markpollack.workflow.batch.durable.RunSnapshot;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import io.github.markpollack.workflow.micronaut.Greetings.Greet;
import io.github.markpollack.workflow.micronaut.Greetings.Greeting;
import io.github.markpollack.workflow.micronaut.Greetings.Request;
import io.github.markpollack.workflow.micronaut.Greetings.Shout;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.exceptions.BeanInstantiationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Micronaut integration builds the workflow beans from {@code agent-workflow.*}. */
class WorkflowFactoryTest {

	@TempDir
	Path directory;

	private Map<String, Object> store(String... more) {
		Map<String, Object> properties = new HashMap<>(Map.of("agent-workflow.database",
				this.directory.resolve("runs").toString(), "agent-workflow.application-id", "greeting",
				"agent-workflow.build-id", "test-1"));
		for (int i = 0; i < more.length; i += 2) {
			properties.put(more[i], more[i + 1]);
		}
		return properties;
	}

	@SuppressWarnings("unchecked")
	private static Greeting run(ApplicationContext context) {
		StepRegistry steps = context.getBean(StepRegistry.class);
		ValidatedWorkflow workflow = Workflows.define("greeting")
			.then((Step<Request, Greeting>) steps.steps().get("greet"))
			.then(context.getBean(Shout.class))
			.terminate(Terminal.SUCCEEDED)
			.build();
		DurableWorkflows runtime = context.getBean(DurableWorkflows.class);
		RunSnapshot run = runtime.start(workflow, "run-1", new Request("Ada"));
		assertThat(runtime.resume(run.runId(), workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
		return (Greeting) runtime.result(run.runId(), workflow);
	}

	@Test
	void theRuntimeRunsTheStepBeans() {
		try (var context = ApplicationContext.run(store())) {
			assertThat(run(context)).isEqualTo(new Greeting("HELLO ADA"));
		}
	}

	@Test
	void stepBeansAreRegisteredByNameOrClassName() {
		try (var context = ApplicationContext.run(store())) {
			StepRegistry steps = context.getBean(StepRegistry.class);
			assertThat(steps.steps()).containsOnlyKeys("greet", Shout.class.getName());
			assertThat(steps.steps().get("greet")).isInstanceOf(Greet.class);
		}
	}

	@Test
	void anApplicationReplacesOneStep() {
		try (var context = ApplicationContext.run(store("test.replace-greet", "true"))) {
			assertThat(context.getBean(StepRegistry.class).steps()).containsOnlyKeys("greet", Shout.class.getName());
			assertThat(run(context)).isEqualTo(new Greeting("WELCOME ADA"));
		}
	}

	@Test
	void propertiesReachThePolicyAndTheCompatibilityFacts() {
		try (var context = ApplicationContext.run(store("agent-workflow.execution.maximum-attempts", "5",
				"agent-workflow.execution.maximum-concurrency", "2", "agent-workflow.configuration.prefix", "Hello"))) {
			assertThat(context.getBean(ExecutionPolicy.class)).isEqualTo(new ExecutionPolicy(5,
					ExecutionPolicy.DEFAULT.maximumDepth(), ExecutionPolicy.DEFAULT.maximumInvocations(), 2));
			ExecutionCompatibility.Manifest manifest = context.getBean(ExecutionCompatibility.class).manifest();
			assertThat(manifest.applicationId()).isEqualTo("greeting");
			assertThat(manifest.buildId()).isEqualTo("test-1");
			assertThat(manifest.configurationDigest()).isEqualTo(
					new ExecutionCompatibility("x", "y", Map.of("prefix", "Hello")).manifest().configurationDigest());
		}
	}

	@Test
	void noRuntimeIsOpenedWithoutADatabase() {
		try (var context = ApplicationContext.run()) {
			assertThat(context.findBean(DurableWorkflows.class)).isEmpty();
			assertThat(context.findBean(ExecutionCompatibility.class)).isEmpty();
			assertThat(context.getBean(ExecutionPolicy.class)).isEqualTo(ExecutionPolicy.DEFAULT);
		}
	}

	@Test
	void anApplicationPolicyReplacesTheDefault() {
		try (var context = ApplicationContext.run(Map.of("test.application-policy", "true"))) {
			assertThat(context.getBean(ExecutionPolicy.class).maximumAttempts()).isEqualTo(1);
		}
	}

	@Test
	void aDatabaseRequiresTheApplicationAndBuildIds() {
		try (var context = ApplicationContext
			.run(Map.of("agent-workflow.database", this.directory.resolve("runs").toString()))) {
			assertThatThrownBy(() -> context.getBean(DurableWorkflows.class))
				.isInstanceOf(BeanInstantiationException.class)
				.hasStackTraceContaining("agent-workflow.application-id is required with agent-workflow.database");
		}
	}

}
