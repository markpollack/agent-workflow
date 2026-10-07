package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ParallelRecoveryIT {

	@Test
	void killedOwnerReusesCommittedQualityAndRedeliversOnlyUnresolvedBackport() throws Exception {
		var directory = Path.of("target/durable-evidence/parallel/" + UUID.randomUUID()).toAbsolutePath();
		Files.createDirectories(directory);
		var mapper = new ObjectMapper();
		var first = launch(directory, "crash");
		try {
			long limit = System.nanoTime() + Duration.ofSeconds(30).toNanos();
			while (!Files.exists(directory.resolve("kill-point.json"))) {
				if (!first.isAlive() || System.nanoTime() > limit)
					throw new AssertionError(Files.readString(directory.resolve("crash.log")));
				Thread.sleep(10);
			}
			assertThat(mapper.readTree(Files.readString(directory.resolve("kill-point.json"))).path("pid").asLong())
				.isEqualTo(first.pid());
			first.destroyForcibly();
			assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
			assertThat(first.exitValue()).isNotZero();
			var recovery = launch(directory, "recover");
			try {
				assertThat(recovery.waitFor(40, TimeUnit.SECONDS)).isTrue();
				assertThat(recovery.exitValue()).withFailMessage(Files.readString(directory.resolve("recover.log")))
					.isZero();
			}
			finally {
				if (recovery.isAlive())
					recovery.destroyForcibly();
			}
			var before = mapper.readTree(Files.readString(directory.resolve("recover-before.json")));
			var after = mapper.readTree(Files.readString(directory.resolve("recover-after.json")));
			assertThat(after.path("status").asText()).isEqualTo("SUCCEEDED");
			assertThat(after.path("deadline")).isEqualTo(before.path("deadline"));
			JsonNode committed = null;
			for (var call : before.path("invocations"))
				if (call.path("placement").asText().contains("quality"))
					committed = call;
			assertThat(committed).isNotNull();
			assertThat(committed.path("outcome").asText()).isEqualTo("COMMITTED");
			assertThat(after.path("values").path(committed.path("output").asText()))
				.isEqualTo(before.path("values").path(committed.path("output").asText()));
			assertThat(Files.readAllLines(directory.resolve("quality.calls"))).hasSize(1);
			assertThat(Files.readAllLines(directory.resolve("backport.calls"))).hasSize(2)
				.allMatch(s -> s.endsWith(":42:sha-42"));
			assertThat(Files.readAllLines(directory.resolve("backport.calls"))
				.stream()
				.map(s -> s.split(":")[0])
				.distinct()
				.count()).isEqualTo(1);
			assertThat(Files.readAllLines(directory.resolve("report.calls"))).hasSize(1);
			assertThat(Files.readString(directory.resolve("recover-result.txt"))).contains("SUCCEEDED",
					"quality=false:backport=true");
			Files.writeString(directory.resolve("kill-receipt.json"),
					mapper.writeValueAsString(Map.of("killedPid", first.pid(), "exit", first.exitValue(),
							"recoveredPid", recovery.pid(), "method", "Process.destroyForcibly",
							"committedQualityCalls", 1, "unresolvedBackportCalls", 2)));
		}
		finally {
			if (first.isAlive())
				first.destroyForcibly();
		}
	}

	private static Process launch(Path directory, String mode) throws Exception {
		return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				ParallelProcessWorker.class.getName(), directory.toString(), mode)
			.redirectErrorStream(true)
			.redirectOutput(directory.resolve(mode + ".log").toFile())
			.start();
	}

}
