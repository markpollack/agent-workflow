package io.github.markpollack.workflow.batch.durable;

import java.util.Map;

/**
 * A named leaf implementation in a retained execution image. Implement directly with concrete
 * input/output types and a public no-argument constructor. Instances are created per delivery.
 * External effects can repeat after an unresolved delivery; use the logical invocation ID as
 * an external idempotency key where the external service supports it.
 */
public interface DurableOperation<I, O> {
    O execute(I input, DeliveryContext context, Map<String, String> configuration) throws Exception;
}
