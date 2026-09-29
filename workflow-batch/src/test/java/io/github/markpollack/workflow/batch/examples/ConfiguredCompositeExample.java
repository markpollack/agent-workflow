package io.github.markpollack.workflow.batch.examples;

import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import io.github.markpollack.workflow.batch.examples.CompositeRecoveryExample.*;
import java.nio.file.Path;
import java.util.Map;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;

/**
 * Two definitions can share an authored name and shape while selecting different ordinary
 * configured objects. Each poll is nested in another same-named definition. Registry
 * names preserve the distinction when a fresh application reconstructs these objects.
 */
public final class ConfiguredCompositeExample {

	private ConfiguredCompositeExample() {
	}

	public record Services(String fast, String careful, Path evidence) {
		public Settings fastSettings() {
			return new Settings(fast, evidence.resolve("fast"));
		}

		public Settings carefulSettings() {
			return new Settings(careful, evidence.resolve("careful"));
		}

		public Settings sharedSettings() {
			return new Settings("shared", evidence.resolve("shared"));
		}

		public Map<String, String> facts() {
			return Map.of("fast", fast, "careful", careful, "evidence", evidence.toAbsolutePath().toString());
		}
	}

	public static ValidatedWorkflow wrap(ValidatedWorkflow poll) {
		return Workflows.define("wrapper").subWorkflow("poll", poll).terminate(SUCCEEDED).build();
	}

	public static ValidatedWorkflow workflow(Fetch fast, Fetch careful, Assess assess, BuildIndex build,
			Report report) {
		var fastPoll = CompositeRecoveryExample.poll(fast, assess);
		var carefulPoll = CompositeRecoveryExample.poll(careful, assess);
		return Workflows.define("configured-index")
			.subWorkflow("before-index", wrap(fastPoll))
			.then("build-index", build)
			.subWorkflow("after-index", wrap(carefulPoll))
			.then("report", report)
			.terminate(SUCCEEDED)
			.build();
	}

	public static void main(String[] args) {
		var settings = new Services("fast-service", "careful-service", Path.of(args[0]));
		var fast = new Fetch(settings.fastSettings());
		var careful = new Fetch(settings.carefulSettings());
		var assess = new Assess(settings.sharedSettings());
		var build = new BuildIndex(settings.sharedSettings());
		var report = new Report(settings.sharedSettings());
		var workflow = workflow(fast, careful, assess, build, report);
		var registry = StepRegistry.of(Map.of("fetch-fast", fast, "fetch-careful", careful, "assess", assess, "build",
				build, "report", report));
		var compatibility = new ExecutionCompatibility("configured-index", "v1", settings.facts());
		try (var runtime = DurableWorkflows.open(settings.evidence().resolve("runs"), registry, compatibility)) {
			String id = runtime.start(workflow, "job", new JobHandle("job-42")).runId();
			runtime.resume(id, workflow);
			System.out.println(runtime.result(id, workflow));
		}
	}

}
