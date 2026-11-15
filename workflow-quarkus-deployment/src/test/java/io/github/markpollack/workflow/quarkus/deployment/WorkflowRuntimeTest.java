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
package io.github.markpollack.workflow.quarkus.deployment;

import java.util.Map;

import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import io.github.markpollack.workflow.batch.durable.RunSnapshot;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import io.github.markpollack.workflow.quarkus.deployment.Greetings.Greet;
import io.github.markpollack.workflow.quarkus.deployment.Greetings.Greeting;
import io.github.markpollack.workflow.quarkus.deployment.Greetings.Request;
import io.github.markpollack.workflow.quarkus.deployment.Greetings.Shout;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** Properties reach the workflow beans, and the runtime bean runs the step beans. */
class WorkflowRuntimeTest {

	@RegisterExtension
	static final QuarkusUnitTest APP = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(Greetings.class, Request.class, Greeting.class, Greet.class,
				Shout.class))
		.overrideConfigKey("agent-workflow.database", Greetings.store("runs").toString())
		.overrideConfigKey("agent-workflow.application-id", "greeting")
		.overrideConfigKey("agent-workflow.build-id", "test-1")
		.overrideConfigKey("agent-workflow.configuration.prefix", "Hello")
		.overrideConfigKey("agent-workflow.execution.maximum-attempts", "5")
		.overrideConfigKey("agent-workflow.execution.maximum-concurrency", "2");

	@Inject
	DurableWorkflows runtime;

	@Inject
	StepRegistry steps;

	@Inject
	ExecutionPolicy policy;

	@Inject
	ExecutionCompatibility compatibility;

	@Inject
	Greet greet;

	@Inject
	Shout shout;

	@Test
	void theRuntimeRunsTheStepBeans() {
		ValidatedWorkflow workflow = Workflows.define("greeting")
			.then(this.greet)
			.then(this.shout)
			.terminate(Terminal.SUCCEEDED)
			.build();
		RunSnapshot run = this.runtime.start(workflow, "run-1", new Request("Ada"));
		assertThat(this.runtime.resume(run.runId(), workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
		assertThat(this.runtime.result(run.runId(), workflow)).isEqualTo(new Greeting("HELLO ADA"));
	}

	@Test
	void stepBeansAreRegisteredByNameOrClassName() {
		assertThat(this.steps.steps()).containsOnlyKeys("greet", Shout.class.getName());
		assertThat(this.steps.steps().get("greet")).isSameAs(this.greet);
	}

	@Test
	void propertiesReachThePolicyAndTheCompatibilityFacts() {
		assertThat(this.policy).isEqualTo(new ExecutionPolicy(5, ExecutionPolicy.DEFAULT.maximumDepth(),
				ExecutionPolicy.DEFAULT.maximumInvocations(), 2));
		ExecutionCompatibility.Manifest manifest = this.compatibility.manifest();
		assertThat(manifest.applicationId()).isEqualTo("greeting");
		assertThat(manifest.buildId()).isEqualTo("test-1");
		assertThat(manifest.configurationDigest())
			.isEqualTo(new ExecutionCompatibility("x", "y", Map.of("prefix", "Hello")).manifest().configurationDigest());
	}

}
