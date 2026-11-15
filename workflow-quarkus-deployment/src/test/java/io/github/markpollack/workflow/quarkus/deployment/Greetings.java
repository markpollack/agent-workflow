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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/** A one-step greeting workflow's types and steps, shared by the extension tests. */
final class Greetings {

	private Greetings() {
	}

	public record Request(String name) {
	}

	public record Greeting(String text) {
	}

	/** A named step bean. */
	@Singleton
	@Named("greet")
	public static class Greet implements Step<Request, Greeting> {

		@Override
		public Greeting execute(StepContext context, Request input) {
			return new Greeting("Hello " + input.name());
		}

	}

	/** An unnamed step bean, registered under its class name. */
	@Singleton
	public static class Shout implements Step<Greeting, Greeting> {

		@Override
		public Greeting execute(StepContext context, Greeting input) {
			return new Greeting(input.text().toUpperCase(java.util.Locale.ROOT));
		}

	}

	static Path store(String name) {
		try {
			return Files.createTempDirectory("workflow-quarkus-").resolve(name);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
