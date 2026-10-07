package io.github.markpollack.workflow.batch.durable;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime-owned finite leaf capacity. Coordinators and graph boundaries use no slots. */
final class LeafExecutor implements AutoCloseable {

	private final Semaphore capacity;

	private final ThreadLocal<Boolean> inBody = ThreadLocal.withInitial(() -> false);

	private final ThreadPoolExecutor workers;

	LeafExecutor(int maximumConcurrency) {
		capacity = new Semaphore(maximumConcurrency, true);
		var number = new AtomicInteger();
		workers = new ThreadPoolExecutor(maximumConcurrency, maximumConcurrency, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(maximumConcurrency), task -> {
					var thread = new Thread(task, "workflow-step-" + number.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.AbortPolicy());
	}

	void requireCoordinator() {
		if (inBody.get())
			throw new WorkflowRefusal("NESTED_EXECUTION", "a Step must compose nested work through the workflow graph");
	}

	void enteringBody() {
		inBody.set(true);
	}

	void leavingBody() {
		inBody.remove();
	}

	boolean tryAcquire() {
		return capacity.tryAcquire();
	}

	void acquire() throws InterruptedException {
		capacity.acquire();
	}

	void release() {
		capacity.release();
	}

	Executor executor() {
		return workers;
	}

	@Override
	public void close() {
		workers.shutdown();
	}

}
