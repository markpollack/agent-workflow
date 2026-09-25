package io.github.markpollack.workflow.batch.durable;

import java.time.Duration;

/** Finite ownership and crash-delivery policy, persisted with each admission. */
public record ExecutionPolicy(Duration lease, int maximumDeliveries) {
    public static final ExecutionPolicy DEFAULT=new ExecutionPolicy(Duration.ofSeconds(30),3);
    public ExecutionPolicy {
        if (lease==null || lease.isNegative() || lease.isZero() || maximumDeliveries<1)
            throw new IllegalArgumentException("positive lease and delivery allowance required");
        try {
            if (lease.toMillis()<1 || lease.toNanos()<1) throw new IllegalArgumentException("lease below store precision");
        } catch (ArithmeticException ex) { throw new IllegalArgumentException("lease overflow",ex); }
    }
    String identity() { return Digests.fields("execution-policy-v1",lease.toString(),Integer.toString(maximumDeliveries)); }
}
