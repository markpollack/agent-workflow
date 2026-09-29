package io.github.markpollack.workflow.batch.durable;

/**
 * Finite admitted resource limits. Root depth is zero; logical calls count both Steps and
 * composites, once per occurrence. Limits are safety bounds, not capacity claims.
 *
 * @param maximumAttempts physical attempts per leaf invocation
 * @param maximumDepth maximum nested composite depth
 * @param maximumInvocations maximum leaf plus composite occurrences
 */
public record ExecutionPolicy(int maximumAttempts, int maximumDepth, long maximumInvocations) {

	public static final ExecutionPolicy DEFAULT = new ExecutionPolicy(3, 32, 10_000);

	/** Use default composition limits with an explicit physical attempt allowance. */
	public ExecutionPolicy(int maximumAttempts) {
		this(maximumAttempts, 32, 10_000);
	}

	public ExecutionPolicy {
		if (maximumAttempts < 1 || maximumDepth < 1 || maximumInvocations < 1)
			throw new IllegalArgumentException("positive finite execution limits required");
	}

	String identity() {
		return Digests.fields("execution-policy-v4", Integer.toString(maximumAttempts), Integer.toString(maximumDepth),
				Long.toString(maximumInvocations));
	}
}
