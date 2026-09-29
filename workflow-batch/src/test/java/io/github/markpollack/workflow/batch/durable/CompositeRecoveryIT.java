package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class CompositeRecoveryIT {

	@ParameterizedTest(name = "{0}: killed at {1} occurrence {2}")
	@CsvSource({ "K01,BEFORE_COMPOSITE_ENTRY_COMMIT,1,1,0,0,2", "K02,AFTER_PROGRESS_COMMIT,1,2,1,0,2",
			"K03,AFTER_DISPATCH_COMMIT,1,2,2,0,2", "K04,AFTER_HANDLER_RETURN,1,2,2,0,3",
			"K05,AFTER_RESULT_COMMIT,4,3,6,1,2", "K06,AFTER_RESULT_COMMIT,2,2,3,0,2",
			"K07,AFTER_PROGRESS_COMMIT,2,2,3,1,2", "K08,AFTER_RESULT_COMMIT,2,3,4,0,2",
			"K09,AFTER_RESULT_COMMIT,2,2,3,0,2", "K10,AFTER_CANCEL_BEFORE_RETURN,1,2,3,0,1" })
	void killedProcessRecoversScopedFacts(String scenario, String boundary, int occurrence, int scopes, int calls,
			int returns, int fetches) throws Exception {
		Path directory = Path.of("target/durable-evidence/composites", scenario + "-" + UUID.randomUUID())
			.toAbsolutePath();
		Files.createDirectories(directory);
		var mapper = new ObjectMapper();
		Process first = launch(directory, "crash", scenario, boundary, occurrence);
		try {
			long limit = System.nanoTime() + Duration.ofSeconds(30).toNanos();
			Path marker = directory.resolve("kill-point.json");
			while (!Files.exists(marker)) {
				if (!first.isAlive() || System.nanoTime() > limit)
					throw new AssertionError(Files.readString(directory.resolve("crash.log")));
				Thread.sleep(10);
			}
			var point = mapper.readTree(Files.readString(marker));
			assertThat(point.path("pid").asLong()).isEqualTo(first.pid());
			first.destroyForcibly();
			assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
			assertThat(first.exitValue()).isNotZero();
			Files.writeString(directory.resolve("kill-receipt.json"),
					mapper.writeValueAsString(Map.of("pid", first.pid(), "exit", first.exitValue(), "method",
							"Process.destroyForcibly", "scenario", scenario, "boundary", boundary)));
			Process recovered = launch(directory, "recover", scenario, boundary, occurrence);
			try {
				assertThat(recovered.waitFor(40, TimeUnit.SECONDS)).withFailMessage("recovery timeout %s", directory)
					.isTrue();
				assertThat(recovered.exitValue()).withFailMessage(Files.readString(directory.resolve("recover.log")))
					.isZero();
			}
			finally {
				if (recovered.isAlive())
					recovered.destroyForcibly();
			}
			assertThat(recovered.pid()).isNotEqualTo(first.pid());
			var before = mapper.readTree(Files.readString(directory.resolve("recover-before.json")));
			var after = mapper.readTree(Files.readString(directory.resolve("recover-after.json")));
			assertThat(before.path("scopes").size()).isEqualTo(scopes);
			assertThat(before.path("invocations").size()).isEqualTo(calls);
			assertThat(before.path("returns").size()).isEqualTo(returns);
			assertThat(after.path("deadline")).isEqualTo(before.path("deadline"));
			assertThat(after.path("id").asText()).isEqualTo(point.path("run").asText());
			assertThat(Files.readAllLines(directory.resolve("fetch.calls"))).hasSize(fetches);
			if (scenario.equals("K10")) {
				assertThat(after.path("status").asText()).isEqualTo("CANCELLED");
				assertThat(after).isEqualTo(before);
				assertThat(Files.exists(directory.resolve("build.calls"))).isFalse();
				assertThat(Files.exists(directory.resolve("report.calls"))).isFalse();
				assertThat(child(before).path("localOutcome").path("status").asText()).isEqualTo("SUCCEEDED");
				assertThat(child(after).path("lifecycle").asText()).isEqualTo("REVOKED");
			}
			else {
				assertThat(after.path("status").asText()).isEqualTo("SUCCEEDED");
				assertThat(Files.readString(directory.resolve("recover-receipt.json"))).contains("indexed-job-42",
						"primary");
				assertThat(Files.readAllLines(directory.resolve("assess.calls"))).hasSize(2);
				assertThat(Files.readAllLines(directory.resolve("build.calls"))).hasSize(1);
				assertThat(Files.readAllLines(directory.resolve("report.calls"))).hasSize(1);
				assertThat(after.path("returns").size()).isEqualTo(scenario.equals("K08") ? 4 : 2);
				for (JsonNode call : before.path("invocations")) {
					var restored = findCall(after, call.path("id").asText());
					if (call.path("status").asText().equals("SETTLED"))
						assertThat(restored).isEqualTo(call);
					assertThat(restored.path("input")).isEqualTo(call.path("input"));
					assertThat(after.path("values").path(call.path("input").asText()))
						.isEqualTo(before.path("values").path(call.path("input").asText()));
				}
				if (Set.of("K06", "K09").contains(scenario)) {
					var c = child(before);
					assertThat(c.path("lifecycle").asText()).isEqualTo("LOCAL_TERMINAL");
					assertThat(after.path("scopes").path(c.path("id").asText()).path("localOutcome"))
						.isEqualTo(c.path("localOutcome"));
					if (scenario.equals("K09"))
						assertThat(after.path("returns").path(c.path("opening").asText()).path("time").asLong())
							.isGreaterThan(c.path("deadline").asLong());
				}
				if (Set.of("K03", "K04").contains(scenario)) {
					var unresolved = before.path("invocations").get(1);
					var restored = findCall(after, unresolved.path("id").asText());
					assertThat(restored.path("attempts").size()).isEqualTo(2);
					assertThat(restored.path("attempts").get(0).path("disposition").asText()).isEqualTo("REATTEMPTED");
				}
			}
			assertThat(after.path("logicalCount").asLong()).isEqualTo(after.path("invocations").size());
		}
		finally {
			if (first.isAlive())
				first.destroyForcibly();
		}
	}

	private static JsonNode child(JsonNode run) {
		for (JsonNode scope : run.path("scopes"))
			if (!scope.path("parent").asText().isEmpty())
				return scope;
		throw new AssertionError("no child scope");
	}

	private static JsonNode findCall(JsonNode run, String id) {
		for (JsonNode call : run.path("invocations"))
			if (call.path("id").asText().equals(id))
				return call;
		throw new AssertionError("no invocation " + id);
	}

	private static Process launch(Path directory, String mode, String scenario, String boundary, int occurrence)
			throws Exception {
		return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp",
				System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
				CompositeProcessWorker.class.getName(), directory.toString(), mode, scenario, boundary,
				Integer.toString(occurrence))
			.redirectErrorStream(true)
			.redirectOutput(directory.resolve(mode + ".log").toFile())
			.start();
	}

}
