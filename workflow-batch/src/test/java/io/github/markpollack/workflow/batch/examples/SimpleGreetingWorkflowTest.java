package io.github.markpollack.workflow.batch.examples;

import java.nio.file.Path;
import java.util.Map;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Two distinct Step types, one settings source and ordinary singleton beans. */
@SpringJUnitConfig(SimpleGreetingWorkflowTest.Config.class)
class SimpleGreetingWorkflowTest {

	@TempDir
	Path directory;

	public record Request(String name) {
	}

	public record Greeting(String text) {
	}

	public record Receipt(String text) {
	}

	record Settings(String prefix) {
	}

	static final class Greet implements Step<Request, Greeting> {

		private final Settings settings;

		int calls;

		Greet(Settings settings) {
			this.settings = settings;
		}

		public Greeting execute(StepContext context, Request input) {
			calls++;
			return new Greeting(settings.prefix() + input.name());
		}

	}

	static final class MakeReceipt implements Step<Greeting, Receipt> {

		int calls;

		public Receipt execute(StepContext context, Greeting input) {
			calls++;
			return new Receipt(input.text());
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class Config {

		@Bean
		Settings settings() {
			return new Settings("Hello ");
		}

		@Bean
		Greet greet(Settings settings) {
			return new Greet(settings);
		}

		@Bean
		MakeReceipt receipt() {
			return new MakeReceipt();
		}

		@Bean
		ValidatedWorkflow workflow(Greet greet, MakeReceipt receipt) {
			return Workflows.define("greeting-receipt")
				.then(greet)
				.then(receipt)
				.terminate(Terminal.SUCCEEDED)
				.build();
		}

		@Bean
		StepRegistry steps(Map<String, Step<?, ?>> namedSteps) {
			return StepRegistry.of(namedSteps);
		}

		@Bean
		ExecutionCompatibility compatibility(Settings settings) {
			return new ExecutionCompatibility("greeting-example", "example-v1", Map.of("prefix", settings.prefix()));
		}

	}

	@Test
	void executesTheSuppliedBeans(@Autowired ValidatedWorkflow workflow, @Autowired StepRegistry steps,
			@Autowired ExecutionCompatibility compatibility, @Autowired Greet greet, @Autowired MakeReceipt receipt) {
		assertThat(steps.steps().get("greet")).isSameAs(greet);
		assertThat(steps.steps().get("receipt")).isSameAs(receipt);
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), steps, compatibility)) {
			var run = runtime.start(workflow, "customer-17", new Request("Ada"));
			assertThat(greet.calls).isZero();
			assertThat(receipt.calls).isZero();
			assertThat(runtime.resume(run.runId(), workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Receipt("Hello Ada"));
			assertThat(greet.calls).isEqualTo(1);
			assertThat(receipt.calls).isEqualTo(1);
		}
	}

}
