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

import java.util.Locale;

import io.github.markpollack.workflow.batch.durable.ExecutionPolicy;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/** A greeting workflow's types and step beans, shared by the integration tests. */
final class Greetings {

	private Greetings() {
	}

	public record Request(String name) {
	}

	public record Greeting(String text) {
	}

	/** The prepackaged greeting step. */
	@Singleton
	@Named("greet")
	public static class Greet implements Step<Request, Greeting> {

		@Override
		public Greeting execute(StepContext context, Request input) {
			return new Greeting(prefix() + input.name());
		}

		String prefix() {
			return "Hello ";
		}

	}

	/** An unnamed step bean, registered under its class name. */
	@Singleton
	public static class Shout implements Step<Greeting, Greeting> {

		@Override
		public Greeting execute(StepContext context, Greeting input) {
			return new Greeting(input.text().toUpperCase(Locale.ROOT));
		}

	}

	/** An application's replacement for the packaged greeting step. */
	@Singleton
	@Named("greet")
	@Replaces(Greet.class)
	@Requires(property = "test.replace-greet", value = "true")
	public static class Welcome extends Greet {

		@Override
		String prefix() {
			return "Welcome ";
		}

	}

	/** An application's own policy. */
	@Factory
	@Requires(property = "test.application-policy", value = "true")
	public static class ApplicationPolicy {

		@Singleton
		ExecutionPolicy policy() {
			return new ExecutionPolicy(1);
		}

	}

}
