package io.github.markpollack.workflow.batch.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.workflow.batch.durable.ApplicationDeployment;
import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.RunSnapshot;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.compiler.DeadlinePolicy;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Call;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Definition;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.End;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Node;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Op;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.github.markpollack.workflow.batch.examples.SequentialRecoveryExample.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Compiling examples of the current fluent and programmatic construction routes. Both
 * validate the same component model and enter the same durable runtime. These examples
 * deliberately use public APIs only and share the recovery example's injected steps.
 */
class SequentialAuthoringTest {

	@TempDir
	Path directory;

	static ApplicationDeployment application(Path directory) {
		var service = new GreetingService("Hello, ", directory.resolve("greet.calls"));
		var greetStep = new Greet(service);
		var receiptStep = new PrintReceipt("Receipt: %s");
		// Intentionally opposite to execution order: this map is object lookup.
		Map<String, Step<?, ?>> registrations = new LinkedHashMap<>();
		registrations.put("receipt", receiptStep);
		registrations.put("greet", greetStep);
		return new ApplicationDeployment("authoring-example", "example-v1", Map.of("greetingPrefix", "Hello, ",
				"receiptTemplate", "Receipt: %s", "evidenceDirectory", directory.toString()), registrations);
	}

	static ValidatedWorkflow fluent(ApplicationDeployment application) {
		return application.define("greeting-receipt")
			.then("greet")
			.then("receipt")
			.terminate(Terminal.SUCCEEDED)
			.build();
	}

	static ValidatedWorkflow programmatic(ApplicationDeployment application) {
		var greet = application.selection("greet");
		var receipt = application.selection("receipt");
		List<Node> body = List.of(new Call("greet", Op.declared("greet", greet.input(), greet.output())),
				new Call("receipt", Op.declared("receipt", receipt.input(), receipt.output())),
				new End(Terminal.SUCCEEDED, ""));
		var definition = new Definition<>("greeting-receipt", greet.input(), receipt.output(), body, null);
		return ValidatedWorkflow.compileSequential(definition, List.of(greet, receipt), DeadlinePolicy.DEFAULT);
	}

	@Test
	void fluentAndProgrammaticRoutesHaveTheSameBindingsIdentityAndRuntime() {
		var application = application(directory);
		var fluent = fluent(application);
		var programmatic = programmatic(application);
		assertThat(programmatic.authoredIdentity()).isEqualTo(fluent.authoredIdentity());
		assertThat(programmatic.invocations()).isEqualTo(fluent.invocations());
		assertThat(programmatic.values()).isEqualTo(fluent.values());
		assertThat(programmatic.graph().edges()).isEqualTo(fluent.graph().edges());
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), application)) {
			var admitted = runtime.start(fluent, "customer-17", new Request("Ada"));
			assertThat(runtime.start(programmatic, "customer-17", new Request("Ada")).runId())
				.isEqualTo(admitted.runId());
			assertThat(runtime.resume(admitted.runId(), programmatic).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(admitted.runId(), fluent)).isEqualTo(new Receipt("Receipt: Hello, Ada"));
		}
	}

	@Test
	void twoOccurrencesReuseOneRegistrationAndRebuildWithNewObjectsAfterReopening() throws Exception {
		var application = application(directory);
		var firstDefinition = twice(application);
		String runId;
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), application)) {
			runId = runtime.start(firstDefinition, "customer-17", new Request("Ada")).runId();
			assertThat(runtime.advance(runId, firstDefinition).nextOperation()).isEqualTo(1);
		}
		// New service and Step objects, unchanged declared
		// build/configuration/registrations.
		var replacementApplication = application(directory);
		var rebuilt = twice(replacementApplication);
		assertThat(rebuilt.authoredIdentity()).isEqualTo(firstDefinition.authoredIdentity());
		assertThat(rebuilt.invocations()).isEqualTo(firstDefinition.invocations());
		assertThat(rebuilt.invocations().subList(0, 2)).extracting(call -> call.executable().entryPoint())
			.containsExactly("greet", "greet");
		assertThat(rebuilt.invocations().get(0).placement()).isNotEqualTo(rebuilt.invocations().get(1).placement());
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), replacementApplication)) {
			assertThat(runtime.start(rebuilt, "customer-17", new Request("Ada")).runId()).isEqualTo(runId);
			assertThat(runtime.resume(runId, rebuilt).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
			assertThat(runtime.result(runId, rebuilt)).isEqualTo(new Receipt("Receipt: Hello, Ada"));
		}
		var effects = Files.readAllLines(directory.resolve("greet.calls"));
		assertThat(effects).hasSize(2); // The committed first occurrence was reused.
		assertThat(effects).allMatch(line -> line.startsWith(runId + " "));
		assertThat(effects.stream().map(line -> line.split(" ")[1]).toList()).doesNotHaveDuplicates();
	}

	static ValidatedWorkflow twice(ApplicationDeployment application) {
		return application.define("two-greetings")
			.then("first-greeting", "greet")
			.then("second-greeting", "greet")
			.then("receipt")
			.terminate(Terminal.SUCCEEDED)
			.build();
	}

	@Test
	void programmaticConstructionStillRequiresValidationAndCanSupplyATerminalReason() {
		var application = application(directory);
		var greet = application.selection("greet");
		var call = new Call("greet", Op.declared("greet", greet.input(), greet.output()));
		var unfinished = new Definition<>("unfinished", greet.input(), greet.output(), List.<Node>of(call), null);
		assertThatThrownBy(
				() -> ValidatedWorkflow.compileSequential(unfinished, List.of(greet), DeadlinePolicy.DEFAULT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("missing terminal");
		assertThatThrownBy(() -> application.define("failed").then("greet").terminate(Terminal.FAILED).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("non-success terminal reason required");
		var definition = new Definition<>("failed", greet.input(), greet.output(),
				List.of(call, new End(Terminal.FAILED, "demonstration complete")), null);
		var validated = ValidatedWorkflow.compileSequential(definition, List.of(greet), DeadlinePolicy.DEFAULT);
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), application)) {
			var run = runtime.start(validated, "customer-17", new Request("Ada"));
			assertThat(runtime.resume(run.runId(), validated).status()).isEqualTo(RunSnapshot.Status.FAILED);
		}
	}

}
