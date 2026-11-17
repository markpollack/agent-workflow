/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.agentclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.client.DefaultAgentOptions;
import io.github.markpollack.agents.client.ExecutionDecision;
import io.github.markpollack.agents.client.ExecutionRecorder;
import io.github.markpollack.agents.client.NativeGeneration;
import io.github.markpollack.agents.client.NativeRecording;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Support for the live Junie proof, ported from Agent Client's recording conformance
 * suite: the environment-chosen model route, the evidence directory, the separate reader
 * JVM and Journal's verdict.
 */
final class JunieProof {

	static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

	/** The Junie CLI's acquisition profile that Journal's qualifier accepts. */
	static final String PROFILE = "junie-3813.7-acp-session-files";

	private JunieProof() {
	}

	static String env(String name, String fallback) {
		String value = System.getenv(name);
		return (value == null || value.isBlank()) ? fallback : value;
	}

	static boolean available(String executable) {
		try {
			Process process = new ProcessBuilder(executable, "--version").redirectErrorStream(true).start();
			process.getInputStream().readAllBytes();
			return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
		}
		catch (IOException ex) {
			return false;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	static Path evidence() throws IOException {
		String kept = System.getenv("JUNIE_IT_EVIDENCE");
		if (kept != null) {
			return Files.createDirectories(Path.of(kept));
		}
		return Files.createTempDirectory("junie-workflow-it");
	}

	/** Junie's options from the environment, so a local model needs no key. */
	static DefaultAgentOptions options(Path project) {
		var extras = new LinkedHashMap<String, Object>();
		extras.put("skip-update-check", true);
		extras.put("share-anonymous-statistics", "false");
		Optional.ofNullable(System.getenv("JUNIE_IT_PROVIDER")).ifPresent(p -> extras.put("provider", p));
		Optional.ofNullable(System.getenv("JUNIE_IT_LITELLM_URL")).ifPresent(u -> extras.put("litellm-url", u));
		Optional.ofNullable(System.getenv("JUNIE_IT_MODEL_LOCATION")).ifPresent(m -> extras.put("model-location", m));
		var builder = DefaultAgentOptions.builder()
			.workingDirectory(project.toString())
			.timeout(Duration.ofMinutes(10))
			.extras(extras);
		Optional.ofNullable(System.getenv("JUNIE_IT_MODEL")).ifPresent(builder::model);
		return builder.build();
	}

	/**
	 * Reads the archive in a separate JVM whose classpath is Journal's capture TCK only.
	 * The reader refuses to run if an Agent Client or Journal writer class is loadable.
	 */
	static JsonNode read(Path store, String... selectors) throws Exception {
		Path report = Files.createTempDirectory(store.getParent(), "reader").resolve("report.json");
		String classpath = System.getProperty("proof.reader.classes") + java.io.File.pathSeparator
				+ Files.readString(Path.of(System.getProperty("proof.reader.classpath"))).trim();
		var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", classpath, "io.github.markpollack.workflow.agentclient.reader.RecordingArchiveReader",
				store.toString(), report.toString()));
		command.addAll(List.of(selectors));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("reader finished: " + output).isTrue();
		assertThat(process.exitValue()).as(output).isZero();
		return JSON.readTree(report.toFile());
	}

	/**
	 * Journal's verdict for a Junie 3813.7 session: ACCEPTED, with no defect, no
	 * unsupported item and no unresolved reason.
	 */
	static void assertVerdict(JsonNode report) {
		String detail = report.path("acceptanceDetail").asText();
		var counts = java.util.regex.Pattern.compile("\"(DEFECTIVE|UNSUPPORTED|UNRESOLVED)\" : (\\d+)")
			.matcher(detail);
		while (counts.find()) {
			assertThat(Integer.parseInt(counts.group(2))).as(counts.group(1) + " in " + detail).isZero();
		}
		assertThat(detail.lines()).as(detail)
			.noneMatch(line -> line.matches(".*\\] (DEFECTIVE|UNSUPPORTED|UNRESOLVED): .*"));
		assertThat(report.path("acceptance").asText()).as(detail).isEqualTo("ACCEPTED");
	}

	/**
	 * Every usage member the export carries is one the summary reports; a cost of zero
	 * is a valid report.
	 */
	static void assertUsageAsReported(Path summaryFile, JsonNode report) throws IOException {
		JsonNode summary = JSON.readTree(summaryFile.toFile());
		for (JsonNode entry : summary.path("mainAgent").path("modelUsage")) {
			if (entry.has("cost")) {
				assertThat(entry.path("cost").decimalValue()).as("reported cost").isNotNegative();
			}
		}
		assertThat(report.path("usage")).as("usage objects in the canonical export").isNotNull();
		report.path("usage").forEach(usage -> usage.fields().forEachRemaining(member -> {
			if (member.getValue().isNumber()) {
				assertThat(member.getValue().decimalValue()).as(member.getKey()).isNotNegative();
			}
		}));
	}

	static void writeEvidence(Path evidence, AgentClientResponse response, ExecutionDecision decision,
			NativeGeneration generation, JsonNode manifest, NativeRecording observed, JsonNode report,
			boolean qualifier, Map<String, Object> workflow) throws IOException {
		var index = new LinkedHashMap<String, Object>();
		index.put("journalJunieQualifier", qualifier);
		index.put("workflow", workflow);
		index.put("executionId", decision.executionId());
		index.put("decisionId", decision.decisionId());
		index.put("sessionId", decision.sessionId());
		index.put("connectionId", generation.connectionId());
		index.put("acpSessionId", manifest.path("process").path("acp_session_id").asText());
		index.put("nativeSessionsNew", manifest.path("process").path("native_sessions_new"));
		index.put("nativeTaskId", manifest.path("turns").path(0).path("native_task_uid").asText());
		index.put("cliVersion", manifest.path("run").path("cli").path("version").asText());
		index.put("acquisitionProfile", generation.acquisitionProfile());
		index.put("manifest", generation.manifest().toString());
		index.put("manifestSha256", generation.manifestSha256());
		index.put("complete", generation.complete());
		index.put("detail", generation.detail());
		index.put("outcome", observed.outcome().name());
		index.put("terminalProfile", observed.terminalProfile());
		index.put("recordingDetail", observed.detail());
		index.put("successful", response.isSuccessful());
		index.put("answer", response.getResult());
		index.put("acceptance", report.path("acceptance").asText());
		Files.writeString(evidence.resolve("evidence-index.json"), JSON.writeValueAsString(index),
				StandardCharsets.UTF_8);
		Files.writeString(evidence.resolve("reader-report.json"), JSON.writeValueAsString(report),
				StandardCharsets.UTF_8);
	}

	/**
	 * A recorder that delegates to the shipped one and keeps what it decided, since a
	 * synchronous {@code run()} returns only the response.
	 */
	static final class CapturingRecorder implements ExecutionRecorder {

		final CompletableFuture<ExecutionDecision> decision = new CompletableFuture<>();

		final CompletableFuture<Optional<NativeGeneration>> generation = new CompletableFuture<>();

		final CompletableFuture<NativeRecording> recording = new CompletableFuture<>();

		private final ExecutionRecorder delegate;

		CapturingRecorder(ExecutionRecorder delegate) {
			this.delegate = delegate;
		}

		@Override
		public Supplier<String> prepare(ExecutionDecision decision) {
			return this.delegate.prepare(decision);
		}

		@Override
		public boolean takesNativeGeneration() {
			return this.delegate.takesNativeGeneration();
		}

		@Override
		public Prepared prepareRecording(ExecutionDecision decision, Optional<NativeGeneration> generation) {
			this.decision.complete(decision);
			this.generation.complete(generation);
			var prepared = this.delegate.prepareRecording(decision, generation);
			this.recording.complete(prepared.nativeRecording());
			return prepared;
		}

	}

}
