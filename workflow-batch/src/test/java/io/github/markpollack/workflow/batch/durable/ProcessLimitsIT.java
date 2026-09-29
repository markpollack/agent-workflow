package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.workflow.flows.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ProcessLimitsIT {

	@ParameterizedTest
	@ValueSource(strings = { "allowance", "deadline" })
	void killedProcessCannotRenewAllowanceOrDeadline(String limit) throws Exception {
		Path directory = Path.of("target/durable-evidence/process-limits", limit + "-" + UUID.randomUUID())
			.toAbsolutePath();
		Files.createDirectories(directory);
		var mapper = new ObjectMapper();
		Process first = launch(directory, limit, "crash");
		try {
			long timeout = System.nanoTime() + Duration.ofSeconds(30).toNanos();
			while (!Files.exists(directory.resolve("ready.json"))) {
				if (!first.isAlive() || System.nanoTime() > timeout)
					throw new AssertionError(Files.readString(directory.resolve("crash.log")));
				Thread.sleep(10);
			}
			var ready = mapper.readTree(Files.readString(directory.resolve("ready.json")));
			assertThat(ready.path("pid").asLong()).isEqualTo(first.pid());
			first.destroyForcibly();
			assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
			assertThat(first.exitValue()).isNotZero();
			var before = mapper.readTree(Files.readString(directory.resolve("before.json")));
			if (limit.equals("deadline")) {
				long remaining = before.path("deadline").asLong() - System.currentTimeMillis();
				if (remaining > 0)
					Thread.sleep(remaining + 50);
			}
			Process recovered = launch(directory, limit, "recover");
			try {
				assertThat(recovered.waitFor(30, TimeUnit.SECONDS)).isTrue();
				assertThat(recovered.exitValue()).withFailMessage(Files.readString(directory.resolve("recover.log")))
					.isZero();
			}
			finally {
				if (recovered.isAlive())
					recovered.destroyForcibly();
			}
			var after = mapper.readTree(Files.readString(directory.resolve("after.json")));
			assertThat(after.path("deadline")).isEqualTo(before.path("deadline"));
			assertThat(after.path("node")).isEqualTo(before.path("node"));
			assertThat(after.path("values")).isEqualTo(before.path("values"));
			assertThat(after.path("invocations").get(0).path("attempts")).hasSize(1);
			assertThat(after.path("status").asText()).isEqualTo("FAILED");
			assertThat(after.path("reasonCode").asText())
				.isEqualTo(limit.equals("allowance") ? "ATTEMPTS_EXHAUSTED" : "DEADLINE_EXCEEDED");
			assertThat(Files.exists(directory.resolve("effect.txt"))).isFalse();
			Files.writeString(directory.resolve("kill-receipt.json"),
					mapper.writeValueAsString(Map.of("killedPid", first.pid(), "exit", first.exitValue(),
							"recoveredPid", recovered.pid(), "limit", limit, "method", "Process.destroyForcibly")));
		}
		finally {
			if (first.isAlive())
				first.destroyForcibly();
		}
	}

	private static Process launch(Path directory, String limit, String mode) throws Exception {
		return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				Worker.class.getName(), directory.toString(), limit, mode)
			.redirectErrorStream(true)
			.redirectOutput(directory.resolve(mode + ".log").toFile())
			.start();
	}

	public static class Worker {

		public record Input(String text) {
		}

		static class Effect implements Step<Input, Input> {

			private final Path directory;

			Effect(Path directory) {
				this.directory = directory;
			}

			public Input execute(StepContext context, Input input) {
				try {
					Files.writeString(directory.resolve("effect.txt"), input.text());
					return input;
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}

		}

		public static void main(String[] args) throws Exception {
			Path directory = Path.of(args[0]), database = directory.resolve("runs");
			String limit = args[1], mode = args[2];
			var step = new Effect(directory);
			var workflow = Workflows.define("limit")
				.then(step)
				.maxDuration(Duration.ofSeconds(limit.equals("deadline") ? 6 : 60))
				.terminate(Terminal.SUCCEEDED)
				.build();
			var policy = new ExecutionPolicy(limit.equals("allowance") ? 1 : 3);
			BoundaryHooks hooks = (point, id) -> {
				if (mode.equals("crash") && point.equals("AFTER_DISPATCH_COMMIT")) {
					try {
						Files.writeString(directory.resolve("before.json"), StoreTestSupport.state(database, id));
						Files.writeString(directory.resolve("ready.tmp"), new ObjectMapper()
							.writeValueAsString(Map.of("pid", ProcessHandle.current().pid(), "runId", id)));
						Files.move(directory.resolve("ready.tmp"), directory.resolve("ready.json"),
								StandardCopyOption.ATOMIC_MOVE);
						while (true)
							java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
					}
					catch (Exception ex) {
						throw new IllegalStateException(ex);
					}
				}
			};
			try (var runtime = new DurableWorkflows(database, StepRegistry.of(Map.of("effect", step)),
					new ExecutionCompatibility("limits", "v1", Map.of()), policy, hooks)) {
				String id = mode.equals("crash") ? runtime.start(workflow, "one", new Input("exact")).runId()
						: new ObjectMapper().readTree(Files.readString(directory.resolve("ready.json")))
							.path("runId")
							.asText();
				var result = runtime.resume(id, workflow);
				if (!result.terminal())
					throw new AssertionError(result);
				Files.writeString(directory.resolve("after.json"), StoreTestSupport.state(database, id));
			}
		}

	}

}
