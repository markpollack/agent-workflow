package io.github.markpollack.workflow.batch.examples;

import java.nio.file.Path;
import java.util.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import io.github.markpollack.workflow.batch.durable.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.assertj.core.api.Assertions.*;

/** Application settings and typed launch input have independent lifetimes. */
@SpringJUnitConfig({ GreetingWorkflowTest.Config.class, GreetingWorkflowTest.DefaultSettings.class })
class GreetingWorkflowTest {

	@TempDir
	Path directory;

	public record Text(String value) {
	}

	public record Receipt(String text) {
	}

	public record ExampleSettings(String warmPrefix, String formalPrefix) {
	}

	static final class TextService {

		String prefix(String prefix, String text) {
			return prefix + text;
		}

	}

	static final class Prefix implements Step<Text, Text> {

		private final TextService service;

		private final String prefix;

		final List<String> invocations = new ArrayList<>();

		Prefix(TextService service, String prefix) {
			this.service = service;
			this.prefix = prefix;
		}

		public Text execute(StepContext context, Text input) {
			invocations.add(context.invocationId());
			return new Text(service.prefix(prefix, input.value()));
		}

	}

	static final class MakeReceipt implements Step<Text, Receipt> {

		final List<String> invocations = new ArrayList<>();

		public Receipt execute(StepContext context, Text input) {
			invocations.add(context.invocationId());
			return new Receipt(input.value());
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class DefaultSettings {

		@Bean
		ExampleSettings settings() {
			return new ExampleSettings("Hello ", "Dear ");
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class Config {

		@Bean
		TextService textService() {
			return new TextService();
		}

		@Bean("greet-warm")
		Prefix warm(TextService service, ExampleSettings settings) {
			return new Prefix(service, settings.warmPrefix());
		}

		@Bean("greet-formal")
		Prefix formal(TextService service, ExampleSettings settings) {
			return new Prefix(service, settings.formalPrefix());
		}

		@Bean("unused")
		Prefix unused(TextService service) {
			return new Prefix(service, "unused ");
		}

		@Bean
		MakeReceipt receipt() {
			return new MakeReceipt();
		}

		@Bean
		ValidatedWorkflow workflow(@Qualifier("greet-warm") Prefix warm, @Qualifier("greet-formal") Prefix formal,
				MakeReceipt receipt) {
			return Workflows.define("greeting-receipt")
				.then("warm-first", warm)
				.then("formal", formal)
				.then("warm-again", warm)
				.then("receipt", receipt)
				.terminate(Terminal.SUCCEEDED)
				.build();
		}

		@Bean
		StepRegistry steps(Map<String, Step<?, ?>> namedSteps) {
			return StepRegistry.of(namedSteps);
		}

		@Bean
		ExecutionCompatibility compatibility(ExampleSettings settings) {
			return new ExecutionCompatibility("greeting-example", "example-v1",
					Map.of("warm.prefix", settings.warmPrefix(), "formal.prefix", settings.formalPrefix()));
		}

	}

	@Test
	void executesTheSuppliedBeansAndAcceptsANewBusinessInput(@Autowired ValidatedWorkflow workflow,
			@Autowired StepRegistry steps, @Autowired ExecutionCompatibility compatibility,
			@Autowired @Qualifier("greet-warm") Prefix warm, @Autowired @Qualifier("greet-formal") Prefix formal,
			@Autowired MakeReceipt receipt, @Autowired @Qualifier("unused") Prefix unused) {
		warm.invocations.clear();
		formal.invocations.clear();
		receipt.invocations.clear();
		unused.invocations.clear();
		assertThat(steps.steps().get("greet-warm")).isSameAs(warm);
		assertThat(workflow.suppliedSteps().values()).filteredOn(s -> s == warm).hasSize(2);
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), steps, compatibility)) {
			var run = runtime.start(workflow, "customer-17", new Text("Ada"));
			assertThat(warm.invocations).isEmpty(); // Admission/preparation executes no
													// business code.
			assertThat(runtime.inspect(run.runId())).isEqualTo(run);
			assertThat(runtime.discover()).extracting(RunSnapshot::runId).containsExactly(run.runId());
			var completed = runtime.resume(run.runId(), workflow);
			assertThat(completed.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(run.runId(), workflow)).isEqualTo(new Receipt("Hello Dear Hello Ada"));
			assertThat(warm.invocations).hasSize(2).doesNotHaveDuplicates();
			assertThat(formal.invocations).hasSize(1);
			assertThat(receipt.invocations).hasSize(1);
			assertThat(completed.selectedSteps().values()).containsExactlyInAnyOrder("greet-warm", "greet-formal",
					"greet-warm", "receipt");
			assertThat(unused.invocations).isEmpty();
			var other = runtime.start(workflow, "customer-18", new Text("Grace"));
			runtime.resume(other.runId(), workflow);
			assertThat(runtime.result(other.runId(), workflow)).isEqualTo(new Receipt("Hello Dear Hello Grace"));
			assertThat(other.runId()).isNotEqualTo(run.runId());
		}
	}

	private static AnnotationConfigApplicationContext context(ExampleSettings settings) {
		var context = new AnnotationConfigApplicationContext();
		context.registerBean(ExampleSettings.class, () -> settings);
		context.register(Config.class);
		context.refresh();
		return context;
	}

	@Test
	void freshContextReusesCommittedWorkAndChangedSettingsRefuseBeforeEntry() {
		var settings = new ExampleSettings("Hello ", "Dear ");
		Path database = directory.resolve("recover");
		String id;
		Prefix original;
		try (var context = context(settings);
				var runtime = DurableWorkflows.open(database, context.getBean(StepRegistry.class),
						context.getBean(ExecutionCompatibility.class))) {
			var workflow = context.getBean(ValidatedWorkflow.class);
			original = context.getBean("greet-warm", Prefix.class);
			id = runtime.start(workflow, "one", new Text("Ada")).runId();
			var saved = runtime.advance(id, workflow);
			assertThat(saved.currentNode())
				.isEqualTo(workflow.graph().unconditionalSuccessor(workflow.graph().startNode()));
			assertThat(original.invocations).hasSize(1);
		}
		try (var context = context(new ExampleSettings("Changed ", "Dear "));
				var runtime = DurableWorkflows.open(database, context.getBean(StepRegistry.class),
						context.getBean(ExecutionCompatibility.class))) {
			var before = runtime.inspect(id);
			assertThatThrownBy(() -> runtime.resume(id, context.getBean(ValidatedWorkflow.class)))
				.isInstanceOfSatisfying(WorkflowRefusal.class, ex -> assertThat(ex.code()).isEqualTo("COMPATIBILITY"));
			assertThat(runtime.inspect(id)).isEqualTo(before);
			assertThat(context.getBean("greet-warm", Prefix.class).invocations).isEmpty();
		}
		try (var context = context(settings);
				var runtime = DurableWorkflows.open(database, context.getBean(StepRegistry.class),
						context.getBean(ExecutionCompatibility.class))) {
			var workflow = context.getBean(ValidatedWorkflow.class);
			var replacement = context.getBean("greet-warm", Prefix.class);
			assertThat(replacement).isNotSameAs(original);
			assertThat(runtime.start(workflow, "one", new Text("Ada")).runId()).isEqualTo(id);
			var completed = runtime.resume(id, workflow);
			assertThat(completed.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(id, workflow)).isEqualTo(new Receipt("Hello Dear Hello Ada"));
			assertThat(replacement.invocations).hasSize(1); // Only warm-again; warm-first
															// was committed.
			assertThat(context.getBean("greet-formal", Prefix.class).invocations).hasSize(1);
			assertThat(context.getBean("unused", Prefix.class).invocations).isEmpty();
			assertThat(runtime.resume(id, workflow)).isEqualTo(completed);
		}
	}

}
