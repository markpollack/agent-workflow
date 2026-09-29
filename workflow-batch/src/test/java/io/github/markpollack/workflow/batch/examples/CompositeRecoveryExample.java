package io.github.markpollack.workflow.batch.examples;

import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;
import java.nio.file.*;
import java.util.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;

/**
 * Reuse a two-Step poll before and after indexing. Each poll has private saved progress
 * inside the same run. Stop this JVM after any advance, then run it again with the same
 * database and settings to reuse accepted work.
 */
public final class CompositeRecoveryExample {

	private CompositeRecoveryExample() {
	}

	public record JobHandle(String id) {
	}

	public record FetchResult(String job, String service) {
	}

	public record JobStatus(String job, String service) {
	}

	public record IndexReport(String job, String service) {
	}

	/**
	 * Ordinary settings construct dependencies and also supply declared compatibility
	 * facts.
	 */
	public record Settings(String service, Path evidence) {
		public Map<String, String> facts() {
			return Map.of("service", service, "evidence", evidence.toAbsolutePath().toString());
		}
	}

	public static final class Fetch implements Step<JobHandle, FetchResult> {

		private final Settings settings;

		public Fetch(Settings settings) {
			this.settings = settings;
		}

		public FetchResult execute(StepContext context, JobHandle input) {
			record(settings.evidence(), "fetch", context, input.id());
			return new FetchResult(input.id(), settings.service());
		}

	}

	public static final class Assess implements Step<FetchResult, JobStatus> {

		private final Settings settings;

		public Assess(Settings settings) {
			this.settings = settings;
		}

		public JobStatus execute(StepContext context, FetchResult input) {
			record(settings.evidence(), "assess", context, input.job());
			return new JobStatus(input.job(), input.service());
		}

	}

	public static final class BuildIndex implements Step<JobStatus, JobHandle> {

		private final Settings settings;

		public BuildIndex(Settings settings) {
			this.settings = settings;
		}

		public JobHandle execute(StepContext context, JobStatus input) {
			record(settings.evidence(), "build", context, input.job());
			return new JobHandle("indexed-" + input.job());
		}

	}

	public static final class Report implements Step<JobStatus, IndexReport> {

		private final Settings settings;

		public Report(Settings settings) {
			this.settings = settings;
		}

		public IndexReport execute(StepContext context, JobStatus input) {
			record(settings.evidence(), "report", context, input.job());
			return new IndexReport(input.job(), input.service());
		}

	}

	public static ValidatedWorkflow poll(Fetch fetch, Assess assess) {
		return Workflows.define("poll").then("fetch", fetch).then("assess", assess).terminate(SUCCEEDED).build();
	}

	public static ValidatedWorkflow index(ValidatedWorkflow poll, BuildIndex buildIndex, Report report) {
		return Workflows.define("index")
			.subWorkflow("before-index", poll)
			.then("build-index", buildIndex)
			.subWorkflow("after-index", poll)
			.then("report", report)
			.terminate(SUCCEEDED)
			.build();
	}

	public static void main(String[] args) throws Exception {
		Path directory = Path.of(args[0]);
		Files.createDirectories(directory);
		var settings = new Settings("primary", directory);
		var fetch = new Fetch(settings);
		var assess = new Assess(settings);
		var buildIndex = new BuildIndex(settings);
		var report = new Report(settings);
		var workflow = index(poll(fetch, assess), buildIndex, report);
		var registry = StepRegistry
			.of(Map.of("fetch", fetch, "assess", assess, "buildIndex", buildIndex, "report", report));
		var compatibility = new ExecutionCompatibility("index-example", "v1", settings.facts());
		try (var runtime = DurableWorkflows.open(directory.resolve("runs"), registry, compatibility)) {
			String id = runtime.start(workflow, "index-job", new JobHandle("job-42")).runId();
			var end = runtime.resume(id, workflow);
			System.out
				.println("run=" + id + " scopes=" + end.scopes().size() + " result=" + runtime.result(id, workflow));
		}
	}

	private static void record(Path directory, String step, StepContext context, String input) {
		try {
			Files.createDirectories(directory);
			Files.writeString(directory.resolve(step + ".calls"),
					ProcessHandle.current().pid() + " " + context.runId() + " " + context.invocationId() + " "
							+ context.attemptId() + " " + input + "\n",
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
