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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.markpollack.agents.client.AgentClient;
import io.github.markpollack.agents.client.AgentClientResponse;
import io.github.markpollack.agents.client.NativeRecording;
import io.github.markpollack.agents.client.journal.JournalExecutionRecorder;
import io.github.markpollack.agents.client.junie.JunieClientProvider;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;
import io.github.markpollack.workflow.batch.durable.DurableWorkflows;
import io.github.markpollack.workflow.batch.durable.ExecutionCompatibility;
import io.github.markpollack.workflow.batch.durable.RunSnapshot;
import io.github.markpollack.workflow.batch.durable.StepRegistry;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.Workflows;
import io.github.markpollack.workflow.flows.steps.AgentClientStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live: a durable Workflow run whose {@code AgentClientStep} calls Agent Client's
 * synchronous {@code run()} on Junie through {@link WorkflowAgentClient}. Agent Journal's
 * {@code JournalExecutionRecorder} records the run, Journal's {@code FinalAssembly}
 * assembles it, and a separate JVM reads it back with the {@code AssemblyOracle}, which
 * must answer ACCEPTED.
 *
 * <p>
 * It calls a real model, so it runs only when one is chosen: it is skipped unless
 * {@code JUNIE_IT_MODEL} is set and a Junie CLI is available. The environment:
 * <ul>
 * <li>{@code JUNIE_IT_EXECUTABLE}: the Junie CLI (default {@code junie});</li>
 * <li>{@code JUNIE_IT_MODEL}: Junie's {@code --model}, for example
 * {@code custom:<name>} with a profile in {@code JUNIE_IT_MODEL_LOCATION};</li>
 * <li>{@code JUNIE_IT_MODEL_LOCATION}: a folder of custom model profiles
 * ({@code <name>.json}), for example one whose {@code baseUrl} is a local
 * OpenAI-compatible server, which costs nothing;</li>
 * <li>{@code JUNIE_IT_PROVIDER}, {@code JUNIE_IT_LITELLM_URL}: Junie's {@code --provider}
 * and {@code --litellm-url};</li>
 * <li>{@code JUNIE_IT_EVIDENCE}: a directory to keep the evidence (default: a new
 * temporary directory).</li>
 * </ul>
 */
@Timeout(900)
class JunieWorkflowRecordingIT {

	@Test
	void aWorkflowAgentClientStepRunsJunieAndJournalReadsItBack() throws Exception {
		assumeTrue(System.getenv("JUNIE_IT_MODEL") != null, "JUNIE_IT_MODEL is not set: no live model chosen");
		String executable = JunieProof.env("JUNIE_IT_EXECUTABLE", "junie");
		assumeTrue(JunieProof.available(executable), "Junie CLI " + executable + " is not available");
		Path evidence = JunieProof.evidence();
		Path project = Files.createDirectories(evidence.resolve("project"));
		Files.writeString(project.resolve("README.md"), "A scratch project for one Workflow-driven Junie session.\n");
		Path sessions = Path.of(System.getProperty("user.home"), ".junie", "sessions");
		Path store = evidence.resolve("archive");
		var recorder = new JunieProof.CapturingRecorder(new JournalExecutionRecorder(store, "workflow-junie-live"));
		var provider = JunieClientProvider.builder()
			.executable(executable)
			.workingDirectory(project)
			.nativeSessionsDirectory(sessions)
			.acquisitionDirectory(evidence.resolve("captures"))
			.build();
		try (var client = AgentClient.builder(provider)
			.defaultOptions(JunieProof.options(project))
			.defaultWorkingDirectory(project)
			.executionRecorder(recorder)
			.build()) {
			var lastResponse = new AtomicReference<AgentClientResponse>();
			var lastContext = new AtomicReference<StepContext>();
			var adapter = WorkflowAgentClient.builder(client).onResponse((context, response) -> {
				lastContext.set(context);
				lastResponse.set(response);
			}).build();
			var step = AgentClientStep.of(adapter,
					"Create a file named hello.txt in the project directory containing exactly the word {input}, "
							+ "then reply with the single word done.");
			var definition = Workflows.define("junie-agent-client-proof").then(step).terminate(SUCCEEDED).build();
			var registry = StepRegistry.builder().register("agent", step).build();
			var compatibility = new ExecutionCompatibility("workflow-agent-client-proof", "junie-workflow-proof",
					Map.of("provider", "junie"));
			String runId;
			Object result;
			try (var runtime = DurableWorkflows.open(evidence.resolve("workflow").resolve("runs"), registry,
					compatibility)) {
				var run = runtime.start(definition, "junie-proof", "hello");
				var completed = runtime.resume(run.runId(), definition);
				assertThat(completed.status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
				runId = run.runId();
				result = runtime.result(run.runId(), definition);
			}
			assertThat(result).isEqualTo(lastResponse.get().getResult());
			assertThat(Files.readString(project.resolve("hello.txt")).trim()).isEqualTo("hello");
			var context = lastContext.get();
			assertThat(context.runId()).isEqualTo(runId);

			var decision = recorder.decision.get(60, TimeUnit.SECONDS);
			var generation = recorder.generation.get(60, TimeUnit.SECONDS).orElseThrow();
			NativeRecording observed = recorder.recording.get(60, TimeUnit.SECONDS);
			client.close();
			JsonNode manifest = JunieProof.JSON.readTree(generation.manifest().toFile());
			boolean qualifier = AcquisitionQualifier.forProfile(JunieProof.PROFILE).isPresent();
			JsonNode report = JunieProof.read(store, "executionId=" + decision.executionId());
			var workflow = new LinkedHashMap<String, Object>();
			workflow.put("runId", context.runId());
			workflow.put("invocationId", context.invocationId());
			workflow.put("attemptId", context.attemptId());
			workflow.put("attemptNumber", context.attemptNumber());
			JunieProof.writeEvidence(evidence, lastResponse.get(), decision, generation, manifest, observed, report,
					qualifier, workflow);

			// The Workflow run, invocation and attempt reach Journal's decision record.
			assertThat(report.path("correlation").path("workflowRunId").asText()).isEqualTo(context.runId());
			assertThat(report.path("correlation").path("workflowInvocationId").asText())
				.isEqualTo(context.invocationId());
			assertThat(report.path("correlation").path("workflowAttemptId").asText()).isEqualTo(context.attemptId());
			assertThat(generation.acquisitionProfile()).isEqualTo(JunieProof.PROFILE);
			assertThat(generation.complete()).as(generation.detail()).isTrue();
			assumeTrue(qualifier, "Journal's Junie qualifier is not on the classpath: recorded "
					+ observed.outcome() + " at " + evidence);
			assertThat(observed.outcome()).as(observed.detail()).isEqualTo(NativeRecording.Outcome.ATTACHED);
			assertThat(observed.terminalProfile()).isEqualTo("client-terminal-junie-acp/1");
			assertThat(report.path("forbiddenClassesAbsent").asBoolean()).isTrue();
			assertThat(report.path("matches").asInt()).isEqualTo(1);
			assertThat(report.path("seriousDiagnostics")).isEmpty();
			assertThat(report.path("assemblySource").asText()).isEqualTo("tck");
			assertThat(report.path("assemblyMatchesRecorder").asBoolean()).isTrue();
			JunieProof.assertVerdict(report);
			String session = manifest.path("process").path("acp_session_id").asText();
			JunieProof.assertUsageAsReported(sessions.resolve(session).resolve("summary.json"), report);
		}
	}

}
