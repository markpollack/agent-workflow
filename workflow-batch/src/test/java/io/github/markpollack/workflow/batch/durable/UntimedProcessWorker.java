package io.github.markpollack.workflow.batch.durable;

import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;

/** Deterministic separate-process fixture, also usable to seed a prior finite deployment. */
public final class UntimedProcessWorker {
    public record Text(String value) {}
    static final class Append implements Step<Text, Text> {
        private final Path calls;
        Append(Path calls) { this.calls = calls; }
        public Text execute(StepContext context, Text input) {
            try { Files.writeString(calls, context.invocationId() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
            return new Text(input.value() + "!");
        }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Files.createDirectories(directory);
        String mode = args[1]; boolean untimed = Boolean.parseBoolean(args[2]);
        var append = new Append(directory.resolve("calls.txt"));
        var start = Workflows.define("saved-mode");
        if (untimed) start = start.withoutDeadline();
        else start = start.maxDuration(Duration.ofMinutes(1));
        var workflow = start.then("first", append).then("second", append).terminate(SUCCEEDED).build();
        var registry = StepRegistry.of(Map.of("append", append));
        var compatibility = new ExecutionCompatibility("saved-mode", "v1", Map.of());
        Path file = directory.resolve("runs");
        try (var runtime = DurableWorkflows.open(file, registry, compatibility)) {
            if (!mode.equals("recover")) {
                StoreTestSupport.controlledClock(file, 100_000);
                var run = runtime.start(workflow, "same-run", new Text("a"));
                var saved = runtime.advance(run.runId(), workflow);
                Files.writeString(directory.resolve("before.json"), StoreTestSupport.state(file, run.runId()));
                Files.writeString(directory.resolve("authored.txt"), workflow.authoredIdentity());
                Files.writeString(directory.resolve("implementation.txt"), DurableWorkflows.class.getProtectionDomain().getCodeSource().getLocation().toString());
                if (mode.equals("seed")) return;
                Files.writeString(directory.resolve("kill-point.txt"), run.runId());
                while (true) java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
            }
            StoreTestSupport.time(100_000 + (untimed ? Duration.ofDays(10).toMillis() : 1_000));
            var runs = runtime.discover();
            if (runs.size() != 1 || !Files.readString(directory.resolve("authored.txt")).equals(workflow.authoredIdentity()))
                throw new AssertionError("saved identity/discovery changed");
            var run = runs.getFirst();
            if (run.hasDeadline() == untimed) throw new AssertionError("saved deadline mode changed");
            var savedCall = run.invocations().getFirst();
            var end = runtime.resume(run.runId(), workflow);
            if (end.status() != RunSnapshot.Status.SUCCEEDED || !savedCall.equals(end.invocations().getFirst())
                    || !new Text("a!!").equals(runtime.result(run.runId(), workflow)))
                throw new AssertionError("committed work/result not preserved");
            Files.writeString(directory.resolve("after.json"), StoreTestSupport.state(file, run.runId()));
            System.out.println("PASS " + (untimed ? "untimed" : "finite") + " persisted identity and committed work reuse");
        }
    }
}
