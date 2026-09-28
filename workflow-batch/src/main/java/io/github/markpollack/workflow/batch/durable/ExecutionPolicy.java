package io.github.markpollack.workflow.batch.durable;

/** Finite physical attempt allowance for each logical invocation, persisted at admission. */
public record ExecutionPolicy(int maximumAttempts) {
    public static final ExecutionPolicy DEFAULT = new ExecutionPolicy(3);
    public ExecutionPolicy {
        if (maximumAttempts < 1) throw new IllegalArgumentException("positive attempt allowance required");
    }
    String identity() { return Digests.fields("execution-policy-v3", Integer.toString(maximumAttempts)); }
}
