package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FanOutRecoveryIT {

	@Test
	void killedOwnerReusesManifestCommittedItemsAndLogicalOccupancy() throws Exception {
		var directory = Path.of("target/durable-evidence/fan-out/" + UUID.randomUUID()).toAbsolutePath();
		Files.createDirectories(directory);
		var mapper = new ObjectMapper();
		Files.writeString(directory.resolve("source.txt"), "first\nsecond\nthird\n");
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
			Files.writeString(directory.resolve("source.txt"), "changed\norder\nnew\n");
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
			var group = before.path("groups").elements().next();
			assertThat(group.path("members").size()).isEqualTo(3);
			assertThat(group.path("maxInFlight").asInt()).isEqualTo(2);
			assertThat(after.path("groups").path(group.path("id").asText()).path("members"))
				.isEqualTo(group.path("members"));
			assertThat(after.path("values").path(group.path("manifest").asText()))
				.isEqualTo(before.path("values").path(group.path("manifest").asText()));
			String committedOutput = null;
			for (var call : before.path("invocations"))
				if (call.path("outcome").asText().equals("COMMITTED")
						&& call.path("placement").asText().contains("4:body4:item"))
					committedOutput = call.path("output").asText();
			assertThat(committedOutput).isNotNull();
			assertThat(after.path("values").path(committedOutput))
				.isEqualTo(before.path("values").path(committedOutput));
			assertThat(Files.readAllLines(directory.resolve("discover.calls"))).hasSize(1);
			assertThat(Files.readAllLines(directory.resolve("first.calls"))).hasSize(1);
			var repeated = Files.readAllLines(directory.resolve("second.calls"));
			assertThat(repeated).hasSize(2).allMatch(s -> s.endsWith(":original-sha"));
			assertThat(repeated.stream().map(s -> s.split(":")[0]).distinct().count()).isEqualTo(1);
			assertThat(Files.readAllLines(directory.resolve("third.calls"))).hasSize(1);
			assertThat(Files.readAllLines(directory.resolve("report.calls"))).hasSize(1);
			assertThat(Files.readString(directory.resolve("recover-result.txt")))
				.contains("SUCCEEDED", "first", "second", "third")
				.doesNotContain("changed");
			Files.writeString(directory.resolve("kill-receipt.json"),
					mapper.writeValueAsString(Map.of("killedPid", first.pid(), "exit", first.exitValue(),
							"recoveredPid", recovery.pid(), "method", "Process.destroyForcibly", "discoveryCalls", 1,
							"committedFirstCalls", 1, "unresolvedSecondCalls", 2, "queuedThirdCalls", 1)));
		}
		finally {
			if (first.isAlive())
				first.destroyForcibly();
		}
	}

	private static Process launch(Path directory, String mode) throws Exception {
		return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				FanOutProcessWorker.class.getName(), directory.toString(), mode)
			.redirectErrorStream(true)
			.redirectOutput(directory.resolve(mode + ".log").toFile())
			.start();
	}

}
