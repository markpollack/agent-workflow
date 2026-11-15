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

import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import io.github.markpollack.workflow.batch.durable.RunSnapshot;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAutoConfigurationTest {

	@TempDir
	Path directory;

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(WorkflowAutoConfiguration.class));

	/** The prepackaged workflow arrives the way a JAR ships it: as auto-configuration. */
	private final ApplicationContextRunner packaged = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(PackagedWorkflow.class, WorkflowAutoConfiguration.class));

	public record Request(String name) {
	}

	public record Greeting(String text) {
	}

	static final class Greet implements Step<Request, Greeting> {

		private final String prefix;

		Greet(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Greeting execute(StepContext context, Request input) {
			return new Greeting(this.prefix + input.name());
		}

	}

	/** A prepackaged workflow: its step backs off, so an application can replace it. */
	@Configuration(proxyBeanMethods = false)
	static class PackagedWorkflow {

		@Bean
		@ConditionalOnMissingBean(name = "greet")
		Step<Request, Greeting> greet() {
			return new Greet("Hello ");
		}

		@Bean
		ValidatedWorkflow greeting(Step<Request, Greeting> greet) {
			return Workflows.define("greeting").then(greet).terminate(Terminal.SUCCEEDED).build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ApplicationGreeting {

		@Bean
		Greet greet() {
			return new Greet("Welcome ");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ApplicationPolicy {

		@Bean
		ExecutionPolicy policy() {
			return new ExecutionPolicy(1);
		}

	}

	private String[] store() {
		return new String[] { "agent-workflow.database=" + this.directory.resolve("runs"),
				"agent-workflow.application-id=greeting", "agent-workflow.build-id=test-1" };
	}

	@Test
	void runsThePackagedWorkflowThroughTheRuntimeBean() {
		this.packaged.withPropertyValues(store()).run(context -> {
			DurableWorkflows runtime = context.getBean(DurableWorkflows.class);
			ValidatedWorkflow workflow = context.getBean(ValidatedWorkflow.class);
			RunSnapshot run = runtime.start(workflow, "run-1", new Request("Ada"));
			assertThat(runtime.resume(run.runId(), workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Greeting("Hello Ada"));
		});
	}

	@Test
	void anApplicationReplacesOneStepOfThePackagedWorkflow() {
		this.packaged.withUserConfiguration(ApplicationGreeting.class)
			.withPropertyValues(store())
			.run(context -> {
				DurableWorkflows runtime = context.getBean(DurableWorkflows.class);
				ValidatedWorkflow workflow = context.getBean(ValidatedWorkflow.class);
				RunSnapshot run = runtime.start(workflow, "run-1", new Request("Ada"));
				runtime.resume(run.runId(), workflow);
				assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Greeting("Welcome Ada"));
			});
	}

	@Test
	void propertiesReachThePolicyAndTheCompatibilityFacts() {
		this.packaged.withPropertyValues(store())
			.withPropertyValues("agent-workflow.execution.maximum-attempts=5",
					"agent-workflow.execution.maximum-concurrency=2", "agent-workflow.configuration.prefix=Hello")
			.run(context -> {
				assertThat(context.getBean(ExecutionPolicy.class))
					.isEqualTo(new ExecutionPolicy(5, ExecutionPolicy.DEFAULT.maximumDepth(),
							ExecutionPolicy.DEFAULT.maximumInvocations(), 2));
				ExecutionCompatibility.Manifest manifest = context.getBean(ExecutionCompatibility.class).manifest();
				assertThat(manifest.applicationId()).isEqualTo("greeting");
				assertThat(manifest.buildId()).isEqualTo("test-1");
				assertThat(manifest.configurationDigest()).isEqualTo(
						new ExecutionCompatibility("x", "y", java.util.Map.of("prefix", "Hello"))
							.manifest()
							.configurationDigest());
			});
	}

	@Test
	void noRuntimeIsOpenedWithoutADatabase() {
		this.packaged.run(context -> {
			assertThat(context).hasSingleBean(StepRegistry.class).hasSingleBean(ExecutionPolicy.class);
			assertThat(context).doesNotHaveBean(DurableWorkflows.class)
				.doesNotHaveBean(ExecutionCompatibility.class);
			assertThat(context.getBean(ExecutionPolicy.class)).isEqualTo(ExecutionPolicy.DEFAULT);
		});
	}

	@Test
	void anApplicationPolicyReplacesTheDefault() {
		this.runner.withUserConfiguration(ApplicationPolicy.class).run(context -> {
			assertThat(context).hasSingleBean(ExecutionPolicy.class);
			assertThat(context.getBean(ExecutionPolicy.class).maximumAttempts()).isEqualTo(1);
		});
	}

	@Test
	void aDatabaseRequiresTheApplicationAndBuildIds() {
		this.runner.withPropertyValues("agent-workflow.database=" + this.directory.resolve("runs"))
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("agent-workflow.application-id is required with agent-workflow.database"));
	}

}
