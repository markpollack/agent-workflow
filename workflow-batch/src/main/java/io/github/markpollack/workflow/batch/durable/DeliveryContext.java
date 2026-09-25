package io.github.markpollack.workflow.batch.durable;

import java.time.Instant;

/** Distinct logical invocation, physical delivery and lease generation, with the fixed run deadline. */
public record DeliveryContext(String runId, String invocationId, String deliveryId,
        long generation, int deliveryNumber, Instant deadline) {}
