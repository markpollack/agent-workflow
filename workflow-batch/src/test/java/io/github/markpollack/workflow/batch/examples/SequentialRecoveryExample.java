package io.github.markpollack.workflow.batch.examples;

import io.github.markpollack.workflow.flows.Workflows;

import java.nio.file.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;

/**
 * A supplied service, two concrete typed steps and process recovery using saved progress.
 */
public final class SequentialRecoveryExample {

	public record ExampleSettings(String greetingPrefix, String receiptTemplate) {
	}

	private SequentialRecoveryExample() {
	}

	public record Request(String customer) {
	}

	public record Greeting(String text) {
	}

	public record Receipt(String text) {
	}

	/**
	 * An ordinary application dependency: never serialized or constructed by the runtime.
	 */
	static final class GreetingService {

		private final String prefix;

		private final Path evidence;

		GreetingService(String prefix, Path evidence) {
			this.prefix = prefix;
			this.evidence = evidence;
		}

		Greeting greet(String customer, StepContext context) {
			try {
				Files.writeString(evidence,
						context.runId() + " " + context.invocationId() + " " + context.attemptId() + "\n",
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				return new Greeting(prefix + customer);
			}
			catch (IOException failure) {
				throw new UncheckedIOException("Cannot record greeting effect", failure);
			}
		}

	}

	static final class Greet implements Step<Request, Greeting> {

		private final GreetingService service;

		Greet(GreetingService service) {
			this.service = service;
		}

		public Greeting execute(StepContext context, Request input) {
			report("greet", context);
			return service.greet(input.customer(), context);
		}

	}

	static final class PrintReceipt implements Step<Greeting, Receipt> {

		private final String template;

		PrintReceipt(String template) {
			this.template = template;
		}

		public Receipt execute(StepContext context, Greeting input) {
			report("receipt", context);
			return new Receipt(template.formatted(input.text()));
		}

	}

	private static void report(String step, StepContext context) {
		System.out.printf("STEP %s thread=%s run=%s invocation=%s attempt=%s%n", step, Thread.currentThread().getName(),
				context.runId(), context.invocationId(), context.attemptId());
	}

	/**
	 * Arguments: directory and either run, pause-after-first, or recover. Kill the
	 * pause-after-first JVM after its READY marker, then run recover on the same
	 * directory. The application deliberately blocks main at that marker for the
	 * demonstration; it is not a workflow timer.
	 */
	public static void main(String[] args) throws Exception {
		if (args.length != 2 || !java.util.Set.of("run", "pause-after-first", "recover").contains(args[1]))
			throw new IllegalArgumentException("expected: <directory> <run|pause-after-first|recover>");
		Path directory = Path.of(args[0]).toAbsolutePath().normalize();
		Files.createDirectories(directory);
		String mode = args[1];
		var settings = new ExampleSettings("Hello, ", "Receipt: %s");
		var service = new GreetingService(settings.greetingPrefix(), directory.resolve("greet.calls"));
		var greet = new Greet(service);
		var receipt = new PrintReceipt(settings.receiptTemplate());
		var registry = StepRegistry.builder().register("greet", greet).register("receipt", receipt).build();
		var compatibility = new ExecutionCompatibility("sequential-example", "example-v1",
				Map.of("greetingPrefix", settings.greetingPrefix(), "receiptTemplate", settings.receiptTemplate(),
						"evidenceDirectory", directory.toString()));
		var workflow = Workflows.define("greeting-receipt")
			.then("greet", greet)
			.then("receipt", receipt)
			.terminate(Terminal.SUCCEEDED)
			.build();

		System.out.printf("PROCESS pid=%d thread=%s mode=%s%n", ProcessHandle.current().pid(),
				Thread.currentThread().getName(), mode);
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), registry, compatibility)) {
			var admitted = runtime.start(workflow, "customer-17", new Request("Ada"));
			var saved = runtime.inspect(admitted.runId());
			System.out.printf("SAVED run=%s node=%s status=%s%n", saved.runId(), saved.currentNode(), saved.status());
			if (mode.equals("pause-after-first")) {
				var progress = runtime.advance(admitted.runId(), workflow);
				Files.writeString(directory.resolve("ready.txt"),
						ProcessHandle.current().pid() + " " + admitted.runId() + " " + progress.currentNode());
				System.out.printf("READY pid=%d node=%s; kill this process, then run recover%n",
						ProcessHandle.current().pid(), progress.currentNode());
				System.out.flush();
				new CountDownLatch(1).await();
			}
			var completed = runtime.resume(admitted.runId(), workflow);
			System.out.printf("RESULT run=%s status=%s value=%s%n", completed.runId(), completed.status(),
					runtime.result(completed.runId(), workflow));
		}
	}

}
