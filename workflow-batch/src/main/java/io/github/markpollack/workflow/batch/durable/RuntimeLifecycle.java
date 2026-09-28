package io.github.markpollack.workflow.batch.durable;

import java.time.Duration;
import java.util.*;

/** Keeps runtime drainage and live run exclusion together; no thread or task scheduling. */
final class RuntimeLifecycle {
    private enum State { OPEN, CLOSING, CLOSED }
    private State state = State.OPEN;
    private final Set<String> executing = new HashSet<>();
    private final Map<Thread, Integer> callers = new HashMap<>();
    private int active;

    synchronized Activity enter(String runId) {
        if (state != State.OPEN) throw new WorkflowRefusal("RUNTIME_CLOSED", "runtime is closing or closed");
        if (Thread.currentThread().isInterrupted()) throw new WorkflowRefusal("INTERRUPTED", "caller is already interrupted");
        if (runId != null && !executing.add(runId)) throw new WorkflowRefusal("RUN_BUSY", "run is already executing in this runtime");
        active++;
        callers.merge(Thread.currentThread(), 1, Integer::sum);
        return new Activity(runId);
    }

    synchronized boolean shutdown(Duration timeout, Runnable closeResources) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("negative shutdown timeout");
        long remaining = timeout.toNanos();
        if (state == State.CLOSED) return true;
        if (callers.containsKey(Thread.currentThread())) throw new WorkflowRefusal("CLOSE_FROM_EXECUTION", "close cannot drain its own active call");
        state = State.CLOSING;
        boolean interrupted = Thread.interrupted();
        try {
            long previous = System.nanoTime();
            while (active != 0) {
                if (remaining <= 0) return false;
                try { wait(remaining / 1_000_000, (int) (remaining % 1_000_000)); }
                catch (InterruptedException ex) { interrupted = true; }
                long now = System.nanoTime(); remaining -= now - previous; previous = now;
            }
            if (state == State.CLOSED) return true;
            closeResources.run(); // Ownership is released only after store closure succeeds.
            state = State.CLOSED;
            return true;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    final class Activity implements AutoCloseable {
        private final String runId;
        private boolean interrupted;
        private boolean released;
        private Activity(String runId) { this.runId = runId; }
        /** Defer an application interrupt until required persistence and guard release are complete. */
        void captureInterrupt(boolean threwInterruptedException) {
            interrupted |= Thread.interrupted() | threwInterruptedException;
        }
        boolean interrupted() { return interrupted; }
        @Override public void close() {
            synchronized (RuntimeLifecycle.this) {
                if (released) return;
                released = true;
                if (runId != null) executing.remove(runId);
                active--;
                Thread thread = Thread.currentThread();
                int depth = callers.get(thread);
                if (depth == 1) callers.remove(thread); else callers.put(thread, depth - 1);
                RuntimeLifecycle.this.notifyAll();
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
