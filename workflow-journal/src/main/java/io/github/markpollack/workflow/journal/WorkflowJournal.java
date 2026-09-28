package io.github.markpollack.workflow.journal;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.workflow.flows.workflow.TraceRecorder;

/**
 * Factory for creating journal-backed workflow recorders.
 *
 * <p>Call {@link #registerEventType()} once at application startup so the journal's
 * JSON storage can deserialize {@link WorkflowStepEvent} entries back to their typed form.
 *
 * <p>The application submits observations to the recorder explicitly. This recorder
 * is not connected to durable workflow execution and does not own checkpoints.
 */
public final class WorkflowJournal {

    private WorkflowJournal() {}

    /**
     * Registers {@link WorkflowStepEvent} with the journal's JSON storage for polymorphic
     * deserialization. Call once at application startup, after {@code Journal.configure()}.
     */
    public static void registerEventType() {
        Journal.registerEventType("workflow_step", WorkflowStepEvent.class);
    }

    /**
     * Returns a {@link TraceRecorder} that writes step events into the given run.
     *
     * @param run the active journal run to record into
     * @return a trace recorder backed by the journal run
     */
    public static TraceRecorder forRun(Run run) {
        return new JournalTraceRecorder(run);
    }
}
