package io.github.markpollack.workflow.batch.durable;

import java.time.Instant;
import java.util.List;

/** Immutable inspection facts. Payloads and provenance are snapshots, never writable store handles. */
public record RunSnapshot(String runId,String displayName,Status status,Instant admittedAt,Instant deadline,
        String deadlineOrigin,String authoredIdentity,ApplicationDeployment.Manifest deployment,
        long generation,String owner,Instant leaseUntil,int nextOperation,Reason reason,
        List<Invocation> invocations,List<Value> values,List<Event> events,Lineage lineage) {
    public enum Status { ACTIVE, SUCCEEDED, FAILED, CANCELLED }
    public record Reason(String code,String message,String actor,Instant time) {}
    public record Invocation(String invocationId,String placement,String inputValue,String outputValue,
            String status,List<Delivery> deliveries,String childRunId,String childOutcome,String childReason,Instant settledAt) {
        public Invocation { deliveries=List.copyOf(deliveries); }
    }
    public record Delivery(String deliveryId,int number,long generation,Instant chargedAt,String disposition) {}
    public record Value(String valueId,String declaredType,String shapeDigest,String codec,String payloadDigest,
            byte[] payload,List<String> components,List<String> consumed,String producerInvocation,String sourceRunId,String sourceValueId) {
        public Value { payload=payload.clone();components=List.copyOf(components);consumed=List.copyOf(consumed); }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Value v&&valueId.equals(v.valueId)&&declaredType.equals(v.declaredType)
                    &&shapeDigest.equals(v.shapeDigest)&&codec.equals(v.codec)&&payloadDigest.equals(v.payloadDigest)
                    &&java.util.Arrays.equals(payload,v.payload)&&components.equals(v.components)
                    &&consumed.equals(v.consumed)&&producerInvocation.equals(v.producerInvocation)
                    &&sourceRunId.equals(v.sourceRunId)&&sourceValueId.equals(v.sourceValueId);
        }
        @Override public int hashCode() {
            return 31*java.util.Objects.hash(valueId,declaredType,shapeDigest,codec,payloadDigest,components,consumed,producerInvocation,sourceRunId,sourceValueId)
                    +java.util.Arrays.hashCode(payload);
        }
    }
    public record Event(long sequence,String kind,Instant time,String detail) {}
    /** Root allowance is charged once per accepted child; a child never shares parent values by reference. */
    public record Lineage(String rootRunId,String parentRunId,String parentInvocationId,int depth,
            int acceptedDescendants,int maximumChildDepth,int maximumDescendants) {}
    public RunSnapshot { invocations=List.copyOf(invocations);values=List.copyOf(values);events=List.copyOf(events); }
    public boolean terminal() { return status!=Status.ACTIVE; }
}
