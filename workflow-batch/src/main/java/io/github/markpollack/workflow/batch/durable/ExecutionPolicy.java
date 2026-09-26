package io.github.markpollack.workflow.batch.durable;

import java.time.Duration;

/**
 * Finite ownership, delivery and child resource policy, persisted with each admission.
 *
 * @param lease positive lease duration
 * @param maximumDeliveries positive physical delivery allowance per logical operation
 * @param maximumChildDepth maximum child depth below the root; zero prohibits child creation
 * @param maximumDescendants maximum total accepted children per root; zero prohibits child creation
 */
public record ExecutionPolicy(Duration lease, int maximumDeliveries, int maximumChildDepth, int maximumDescendants) {
    public static final ExecutionPolicy DEFAULT=new ExecutionPolicy(Duration.ofSeconds(30),3);
    /** Default lineage allowance: one child level and at most 64 accepted descendants per root. */
    public ExecutionPolicy(Duration lease,int maximumDeliveries) { this(lease,maximumDeliveries,1,64); }
    public ExecutionPolicy {
        if (lease==null || lease.isNegative() || lease.isZero() || maximumDeliveries<1
                || maximumChildDepth<0 || maximumDescendants<0)
            throw new IllegalArgumentException("positive lease/delivery allowance and nonnegative finite child limits required");
        try {
            if (lease.toMillis()<1 || lease.toNanos()<1) throw new IllegalArgumentException("lease below store precision");
        } catch (ArithmeticException ex) { throw new IllegalArgumentException("lease overflow",ex); }
    }
    String identity() { return Digests.fields("execution-policy-v2",lease.toString(),Integer.toString(maximumDeliveries),
            Integer.toString(maximumChildDepth),Integer.toString(maximumDescendants)); }
}
