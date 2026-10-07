package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.batch.examples.DecisionRecoveryExample;
import io.github.markpollack.workflow.batch.examples.DecisionRecoveryExample.*;

/** Separate process with externally observable transaction boundaries. */
public final class DecisionProcessWorker {

	public static void main(String[] args) throws Exception {
		Path directory = Path.of(args[0]);
		String mode = args[1], boundary = args[2];
		int occurrence = Integer.parseInt(args[3]);
		var settings = new Settings(directory);
		var assess = new Assess(settings);
		var explain = new Explain(settings);
		var choose = new Choose(settings);
		var shortForm = new Render(settings, "short");
		var fullForm = new Render(settings, "full");
		var publish = new Publish(settings);
		var workflow = DecisionRecoveryExample.workflow(assess, explain, choose, shortForm, fullForm, publish);
		var registry = StepRegistry.of(Map.of("assessment", assess, "explanation", explain, "choice", choose, "short",
				shortForm, "full", fullForm, "publish", publish));
		var mapper = new ObjectMapper();
		var seen = new AtomicInteger();
		BoundaryHooks hooks = (point, id) -> {
			if (mode.equals("crash") && point.equals(boundary) && seen.incrementAndGet() == occurrence) {
				try {
					Files.writeString(directory.resolve("kill-point.tmp"), mapper.writeValueAsString(
							Map.of("pid", ProcessHandle.current().pid(), "run", id, "boundary", boundary)));
					Files.move(directory.resolve("kill-point.tmp"), directory.resolve("kill-point.json"),
							StandardCopyOption.ATOMIC_MOVE);
					while (true)
						java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
				}
				catch (Exception failure) {
					throw new IllegalStateException(failure);
				}
			}
		};
		Path file = directory.resolve("runs");
		try (var runtime = new DurableWorkflows(file, registry,
				new ExecutionCompatibility("decision-example", "v1", settings.facts()), ExecutionPolicy.DEFAULT,
				hooks)) {
			String id = runtime.start(workflow, "subject-42", new Subject("subject-42", "original subject")).runId();
			Files.writeString(directory.resolve(mode + "-before.json"), StoreTestSupport.state(file, id));
			var end = runtime.resume(id, workflow);
			Files.writeString(directory.resolve(mode + "-after.json"), StoreTestSupport.state(file, id));
			Files.writeString(directory.resolve(mode + "-result.txt"),
					end.status() + " " + runtime.result(id, workflow));
		}
	}

}
