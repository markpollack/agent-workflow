package io.github.markpollack.workflow.batch.durable;

import java.time.Duration;
import java.util.*;

/**
 * Process-local call accounting for one DurableWorkflows handle. A short synchronized
 * section admits calls, prevents overlapping execution of one run and tracks drainage.
 * Its monitor is not held while application steps or database transactions run.
 * <p>
 * OPEN accepts calls; CLOSING refuses new calls while existing whole calls finish; CLOSED
 * has released resources. This class owns no executor or threads. StoreOwnership
 * separately prevents a second runtime/process from owning the database.
 */
final class RuntimeLifecycle {

	private enum State {

		OPEN, CLOSING, CLOSED

	}

	private State state = State.OPEN;

	private final Set<String> executing = new HashSet<>();

	private final Map<Thread, Integer> callers = new HashMap<>();

	private int active;

	/**
	 * Enter one whole API call. A non-null run ID reserves exclusive live execution of
	 * that run; management calls pass null and can inspect/cancel executing work. Refuse
	 * already interrupted callers before JDBC. The returned Activity must close on this
	 * same thread.
	 * @throws WorkflowRefusal for CLOSING/CLOSED, an interrupted caller or a busy run
	 */
	synchronized Activity enter(String runId) {
		if (state != State.OPEN)
			throw new WorkflowRefusal("RUNTIME_CLOSED", "runtime is closing or closed");
		if (Thread.currentThread().isInterrupted())
			throw new WorkflowRefusal("INTERRUPTED", "caller is already interrupted");
		if (runId != null && !executing.add(runId))
			throw new WorkflowRefusal("RUN_BUSY", "run is already executing in this runtime");
		active++;
		callers.merge(Thread.currentThread(), 1, Integer::sum);
		return new Activity(runId);
	}

	/**
	 * Refuse new calls, then wait for whole entered calls without interrupting them. A
	 * zero/expired timeout leaves CLOSING and resources held. Release resources exactly
	 * once only after drainage; concurrent closers recheck CLOSED after waking. A caller
	 * cannot drain its own active call. Preserve interruptions until shutdown cleanup
	 * completes.
	 * @return true if resources closed; false if active calls outlast the timeout
	 */
	synchronized boolean shutdown(Duration timeout, Runnable closeResources) {
		Objects.requireNonNull(timeout);
		if (timeout.isNegative())
			throw new IllegalArgumentException("negative shutdown timeout");
		long remaining = timeout.toNanos();
		if (state == State.CLOSED)
			return true;
		if (callers.containsKey(Thread.currentThread()))
			throw new WorkflowRefusal("CLOSE_FROM_EXECUTION", "close cannot drain its own active call");
		state = State.CLOSING;
		boolean interrupted = Thread.interrupted();
		try {
			long previous = System.nanoTime();
			while (active != 0) {
				if (remaining <= 0)
					return false;
				try {
					wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
				}
				catch (InterruptedException ex) {
					interrupted = true;
				}
				long now = System.nanoTime();
				remaining -= now - previous;
				previous = now;
			}
			if (state == State.CLOSED)
				return true;
			closeResources.run(); // Ownership is released only after store closure
									// succeeds.
			state = State.CLOSED;
			return true;
		}
		finally {
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * A caller-thread token, held across all transactions and application execution in
	 * one API call. close() releases same-run exclusion/drainage accounting and only then
	 * restores any application interrupt deferred to protect required persistence.
	 */
	final class Activity implements AutoCloseable {

		private final String runId;

		private boolean interrupted;

		private boolean released;

		private Activity(String runId) {
			this.runId = runId;
		}

		/**
		 * Defer an application interrupt until persistence and guard release finish.
		 * Interruptible APIs clear the flag when throwing, so an unchecked wrapper's
		 * cause also counts. Identity tracking prevents cyclic cause chains from hanging
		 * the caller. Always sample/clear the actual flag before entering JDBC again.
		 */
		void captureInterrupt(Throwable failure) {
			interrupted |= Thread.interrupted();
			Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
				if (cause instanceof InterruptedException)
					interrupted = true;
			}
		}

		boolean interrupted() {
			return interrupted;
		}

		@Override
		public void close() {
			synchronized (RuntimeLifecycle.this) {
				if (released)
					return;
				released = true;
				if (runId != null)
					executing.remove(runId);
				active--;
				Thread thread = Thread.currentThread();
				int depth = callers.get(thread);
				if (depth == 1)
					callers.remove(thread);
				else
					callers.put(thread, depth - 1);
				RuntimeLifecycle.this.notifyAll();
			}
			if (interrupted)
				Thread.currentThread().interrupt();
		}

	}

}
