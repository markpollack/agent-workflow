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

import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An application bean replaces the extension's default, and nothing is opened until the
 * runtime is injected.
 */
class ApplicationBeansTest {

	@RegisterExtension
	static final QuarkusUnitTest APP = new QuarkusUnitTest()
		.withApplicationRoot(jar -> jar.addClasses(ApplicationPolicy.class));

	/** The application's own policy. */
	@Singleton
	public static class ApplicationPolicy {

		@Produces
		@Singleton
		ExecutionPolicy policy() {
			return new ExecutionPolicy(1);
		}

	}

	@Inject
	ExecutionPolicy policy;

	@Inject
	StepRegistry steps;

	@Inject
	Instance<DurableWorkflows> runtime;

	@Test
	void theApplicationPolicyReplacesTheDefault() {
		assertThat(this.policy.maximumAttempts()).isEqualTo(1);
	}

	@Test
	void withoutStepBeansTheRegistryIsEmpty() {
		assertThat(this.steps.steps()).isEmpty();
	}

	@Test
	void theRuntimeNeedsADatabase() {
		assertThatThrownBy(() -> this.runtime.get().discover())
			.hasStackTraceContaining("agent-workflow.database is required");
	}

}
