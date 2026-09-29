package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.batch.examples.CompositeRecoveryExample;
import io.github.markpollack.workflow.batch.examples.CompositeRecoveryExample.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;

/**
 * Separate JVM used by CompositeRecoveryIT, with explicit externally observed kill
 * points.
 */
public final class CompositeProcessWorker {

	public static void main(String[] args) throws Exception {
		Path directory = Path.of(args[0]);
		String mode = args[1], scenario = args[2], boundary = args[3];
		int occurrence = Integer.parseInt(args[4]);
		Files.createDirectories(directory);
		var mapper = new ObjectMapper();
		var settings = new Settings("primary", directory);
		var fetch = new Fetch(settings);
		var assess = new Assess(settings);
		var build = new BuildIndex(settings);
		var report = new Report(settings);
		var poll = Workflows.define("poll")
			.maxDuration(Duration.ofMillis(100))
			.then("fetch", fetch)
			.then("assess", assess)
			.terminate(SUCCEEDED)
			.build();
		if (scenario.equals("K08"))
			poll = Workflows.define("wrapper").subWorkflow("poll", poll).terminate(SUCCEEDED).build();
		var workflow = CompositeRecoveryExample.index(poll, build, report);
		var registry = StepRegistry.of(Map.of("report", report, "assess", assess, "fetch", fetch, "buildIndex", build));
		var compatibility = new ExecutionCompatibility("composite-process", "v1", settings.facts());
		AtomicInteger seen = new AtomicInteger();
		BoundaryHooks hook = (point, run) -> {
			if (mode.equals("crash") && !scenario.equals("K10") && point.equals(boundary)
					&& seen.incrementAndGet() == occurrence)
				pause(directory, scenario, point, run, mapper);
		};
		Path file = directory.resolve("runs");
		try (var runtime = new DurableWorkflows(file, registry, compatibility, ExecutionPolicy.DEFAULT, hook)) {
			if (mode.equals("crash"))
				StoreTestSupport.controlledClock(file, 10_000);
			else
				StoreTestSupport.time(scenario.equals("K09") ? 10_101 : 10_000);
			String id = runtime.start(workflow, "index-job", new JobHandle("job-42")).runId();
			Files.writeString(directory.resolve(mode + "-before.json"), StoreTestSupport.state(file, id));
			if (mode.equals("crash") && scenario.equals("K10")) {
				runtime.advance(id, workflow);
				runtime.advance(id, workflow);
				runtime.advance(id, workflow);
				runtime.cancel(id, "owner", "cancel pending return");
				pause(directory, scenario, "AFTER_CANCEL_BEFORE_RETURN", id, mapper);
			}
			var end = runtime.resume(id, workflow);
			Files.writeString(directory.resolve(mode + "-after.json"), StoreTestSupport.state(file, id));
			Files.writeString(directory.resolve(mode + "-receipt.json"),
					mapper.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(), "run", id, "status",
							end.status().name(), "result", end.status() == RunSnapshot.Status.SUCCEEDED
									? runtime.result(id, workflow).toString() : end.reason().code())));
		}
	}

	private static void pause(Path directory, String scenario, String boundary, String run, ObjectMapper mapper) {
		try {
			Path temporary = directory.resolve("kill-point.tmp");
			Files.writeString(temporary, mapper.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(),
					"scenario", scenario, "boundary", boundary, "run", run)));
			Files.move(temporary, directory.resolve("kill-point.json"), StandardCopyOption.ATOMIC_MOVE);
			while (true)
				java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
