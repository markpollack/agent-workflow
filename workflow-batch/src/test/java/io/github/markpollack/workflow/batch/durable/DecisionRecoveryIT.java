package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class DecisionRecoveryIT {

	@ParameterizedTest(name = "kill {0} occurrence {1}")
	@CsvSource({ "BEFORE_RESULT_COMMIT,1,2,1,1", "AFTER_RESULT_COMMIT,1,1,1,1", "AFTER_DISPATCH_COMMIT,2,1,1,1",
			"AFTER_HANDLER_RETURN,2,1,2,1", "BEFORE_RESULT_COMMIT,2,1,2,1", "AFTER_RESULT_COMMIT,2,1,1,1",
			"AFTER_RESULT_COMMIT,3,1,1,1", "BEFORE_RESULT_COMMIT,4,1,1,2", "AFTER_RESULT_COMMIT,4,1,1,1" })
	void killedJvmReusesAcceptedAssessmentsRoutesArmInputsAndCaptures(String boundary, int occurrence, int juryCalls,
			int explanationCalls, int fullCalls) throws Exception {
		Path directory = Path
			.of("target/durable-evidence/decisions", boundary + "-" + occurrence + "-" + UUID.randomUUID())
			.toAbsolutePath();
		Files.createDirectories(directory);
		var mapper = new ObjectMapper();
		Process first = launch(directory, "crash", boundary, occurrence);
		try {
			long limit = System.nanoTime() + Duration.ofSeconds(30).toNanos();
			while (!Files.exists(directory.resolve("kill-point.json"))) {
				if (!first.isAlive() || System.nanoTime() > limit)
					throw new AssertionError(Files.readString(directory.resolve("crash.log")));
				Thread.sleep(10);
			}
			var marker = mapper.readTree(Files.readString(directory.resolve("kill-point.json")));
			assertThat(marker.path("pid").asLong()).isEqualTo(first.pid());
			first.destroyForcibly();
			assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
			assertThat(first.exitValue()).isNotZero();
			Process recovery = launch(directory, "recover", boundary, occurrence);
			try {
				assertThat(recovery.waitFor(40, TimeUnit.SECONDS)).isTrue();
				assertThat(recovery.exitValue()).withFailMessage(Files.readString(directory.resolve("recover.log")))
					.isZero();
			}
			finally {
				if (recovery.isAlive())
					recovery.destroyForcibly();
			}
			assertThat(recovery.pid()).isNotEqualTo(first.pid());
			var before = mapper.readTree(Files.readString(directory.resolve("recover-before.json")));
			var after = mapper.readTree(Files.readString(directory.resolve("recover-after.json")));
			assertThat(after.path("status").asText()).isEqualTo("SUCCEEDED");
			assertThat(after.path("decisions").size()).isEqualTo(2);
			assertThat(after.path("deadline")).isEqualTo(before.path("deadline"));
			for (JsonNode route : before.path("decisions")) {
				var restored = after.path("decisions").path(route.path("invocation").asText());
				for (String fact : List.of("invocation", "outcome", "target", "acceptedAt", "armInvocation",
						"armInput"))
					assertThat(restored.path(fact)).isEqualTo(route.path(fact));
				assertThat(after.path("values").path(route.path("armInput").asText()))
					.isEqualTo(before.path("values").path(route.path("armInput").asText()));
				if (route.path("captures").size() > 0)
					assertThat(restored.path("captures")).isEqualTo(route.path("captures"));
			}
			assertThat(Files.readAllLines(directory.resolve("jury.calls"))).hasSize(juryCalls);
			assertThat(Files.readAllLines(directory.resolve("explain.calls"))).hasSize(explanationCalls)
				.allMatch(line -> line.equals("subject-42:INCONCLUSIVE:2"));
			assertThat(Files.readAllLines(directory.resolve("choose.calls"))).hasSize(1);
			assertThat(Files.readAllLines(directory.resolve("full.calls"))).hasSize(fullCalls);
			assertThat(Files.exists(directory.resolve("short.calls"))).isFalse();
			assertThat(Files.readAllLines(directory.resolve("publish.calls"))).hasSize(1);
			assertThat(Files.readString(directory.resolve("recover-result.txt"))).contains("SUCCEEDED",
					"full:INCONCLUSIVE");
			Files.writeString(directory.resolve("kill-receipt.json"),
					mapper.writeValueAsString(Map.of("killedPid", first.pid(), "exit", first.exitValue(),
							"recoveredPid", recovery.pid(), "method", "Process.destroyForcibly", "boundary", boundary,
							"occurrence", occurrence)));
		}
		finally {
			if (first.isAlive())
				first.destroyForcibly();
		}
	}

	private static Process launch(Path directory, String mode, String boundary, int occurrence) throws Exception {
		return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				DecisionProcessWorker.class.getName(), directory.toString(), mode, boundary,
				Integer.toString(occurrence))
			.redirectErrorStream(true)
			.redirectOutput(directory.resolve(mode + ".log").toFile())
			.start();
	}

}
