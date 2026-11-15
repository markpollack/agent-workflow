package io.github.markpollack.workflow.journal;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.workflow.flows.workflow.StepTransition;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;
import io.github.markpollack.workflow.core.NodeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JournalTraceRecorder")
class JournalTraceRecorderTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        Journal.configure(storage);
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    @Test
    @DisplayName("records deterministic step as WorkflowStepEvent")
    void recordsDeterministicStep() {
        String runId;
        try (Run run = Journal.run("test-experiment").start()) {
            runId = run.id();
            TraceRecorder recorder = WorkflowJournal.forRun(run);

            recorder.record(new StepTransition(
                    "run-123", "test-workflow", null, "fetch-data",
                    Instant.now(), Duration.ofMillis(50), 0L, 0.0, NodeType.DETERMINISTIC
            ));
        }

        List<WorkflowStepEvent> events = storage.loadEvents("test-experiment", runId).stream()
                .filter(WorkflowStepEvent.class::isInstance)
                .map(WorkflowStepEvent.class::cast)
                .toList();
        assertThat(events).hasSize(1);

        WorkflowStepEvent event = events.get(0);
        assertThat(event.workflowRunId()).isEqualTo("run-123");
        assertThat(event.workflowName()).isEqualTo("test-workflow");
        assertThat(event.stepName()).isEqualTo("fetch-data");
        assertThat(event.fromStep()).isNull();
        assertThat(event.nodeType()).isEqualTo(NodeType.DETERMINISTIC);
        assertThat(event.stepDuration()).isEqualTo(Duration.ofMillis(50));
        assertThat(event.type()).isEqualTo("workflow_step");
    }

    @Test
    @DisplayName("records agent step with cost and tokens")
    void recordsAgentStep() {
        String runId;
        try (Run run = Journal.run("test-experiment").start()) {
            runId = run.id();
            TraceRecorder recorder = WorkflowJournal.forRun(run);

            recorder.record(new StepTransition(
                    "run-456", "bom-sync", "start", "analyze-versions",
                    Instant.now(), Duration.ofSeconds(3), 1500L, 0.023, NodeType.AGENT
            ));
        }

        WorkflowStepEvent event = storage.loadEvents("test-experiment", runId).stream()
                .filter(WorkflowStepEvent.class::isInstance)
                .map(WorkflowStepEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(event.tokensUsed()).isEqualTo(1500L);
        assertThat(event.costUsd()).isEqualTo(0.023);
        assertThat(event.fromStep()).isEqualTo("start");
    }

    @Test
    @DisplayName("records multiple steps and step count metric")
    void recordsMultipleSteps() {
        String runId;
        try (Run run = Journal.run("test-experiment").start()) {
            runId = run.id();
            TraceRecorder recorder = WorkflowJournal.forRun(run);

            recorder.record(new StepTransition(
                    "run-789", "workflow", null, "step-a",
                    Instant.now(), Duration.ofMillis(100), 0L, 0.0, NodeType.DETERMINISTIC
            ));
            recorder.record(new StepTransition(
                    "run-789", "workflow", "step-a", "step-b",
                    Instant.now(), Duration.ofMillis(200), 500L, 0.01, NodeType.AGENT
            ));
        }

        List<WorkflowStepEvent> stepEvents = storage.loadEvents("test-experiment", runId).stream()
                .filter(WorkflowStepEvent.class::isInstance)
                .map(WorkflowStepEvent.class::cast)
                .toList();
        assertThat(stepEvents).hasSize(2);
        assertThat(stepEvents).extracting(WorkflowStepEvent::stepName)
                .containsExactly("step-a", "step-b");
    }

    @Test
    @DisplayName("records trace path when present on transition")
    void recordsTracePath() {
        String runId;
        try (Run run = Journal.run("test-experiment").start()) {
            runId = run.id();
            TraceRecorder recorder = WorkflowJournal.forRun(run);

            recorder.record(new StepTransition(
                    "run-trace", "remediate", "scan", "fix",
                    Instant.now(), Duration.ofSeconds(12), 3200L, 0.048, NodeType.AGENT,
                    null, "/tmp/traces/fix-20260528.jsonl"
            ));
        }

        WorkflowStepEvent event = storage.loadEvents("test-experiment", runId).stream()
                .filter(WorkflowStepEvent.class::isInstance)
                .map(WorkflowStepEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(event.tracePath()).isEqualTo("/tmp/traces/fix-20260528.jsonl");
        assertThat(event.toMap()).containsEntry("tracePath", "/tmp/traces/fix-20260528.jsonl");
    }

    @Test
    @DisplayName("omits tracePath from map when null")
    void omitsNullTracePath() {
        String runId;
        try (Run run = Journal.run("test-experiment").start()) {
            runId = run.id();
            TraceRecorder recorder = WorkflowJournal.forRun(run);

            recorder.record(new StepTransition(
                    "run-no-trace", "workflow", null, "step-det",
                    Instant.now(), Duration.ofMillis(20), 0L, 0.0, NodeType.DETERMINISTIC
            ));
        }

        WorkflowStepEvent event = storage.loadEvents("test-experiment", runId).stream()
                .filter(WorkflowStepEvent.class::isInstance)
                .map(WorkflowStepEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(event.tracePath()).isNull();
        assertThat(event.toMap()).doesNotContainKey("tracePath");
    }

    @Test
    @DisplayName("getTrace returns empty list — journal is the source of truth")
    void getTraceReturnsEmpty() {
        try (Run run = Journal.run("test-experiment").start()) {
            TraceRecorder recorder = WorkflowJournal.forRun(run);
            assertThat(recorder.getTrace("any-run-id")).isEmpty();
        }
    }
}
