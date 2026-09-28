package io.github.markpollack.workflow.batch.durable;

import java.time.Instant;
import java.util.List;

/** Immutable inspection facts. Payloads and provenance are snapshots, never writable store handles. */
public record RunSnapshot(String runId,String displayName,Status status,Instant admittedAt,Instant deadline,
        String deadlineOrigin,String authoredIdentity,ApplicationDeployment.Manifest deployment,
        int nextOperation,Reason reason,
        List<Invocation> invocations,List<Value> values,List<Event> events) {
    public enum Status { ACTIVE, SUCCEEDED, FAILED, CANCELLED }
    public record Reason(String code,String message,String actor,Instant time) {}
    public record Invocation(String invocationId,String placement,String inputValue,String outputValue,
            String status,List<Attempt> attempts) {
        public Invocation { attempts=List.copyOf(attempts); }
    }
    public record Attempt(String attemptId,int number,Instant chargedAt,String disposition) {}
    public record Value(String valueId,String declaredType,String shapeDigest,String codec,String payloadDigest,
            byte[] payload,List<String> components,List<String> consumed,String producerInvocation) {
        public Value { payload=payload.clone();components=List.copyOf(components);consumed=List.copyOf(consumed); }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Value v&&valueId.equals(v.valueId)&&declaredType.equals(v.declaredType)
                    &&shapeDigest.equals(v.shapeDigest)&&codec.equals(v.codec)&&payloadDigest.equals(v.payloadDigest)
                    &&java.util.Arrays.equals(payload,v.payload)&&components.equals(v.components)
                    &&consumed.equals(v.consumed)&&producerInvocation.equals(v.producerInvocation);
        }
        @Override public int hashCode() {
            return 31*java.util.Objects.hash(valueId,declaredType,shapeDigest,codec,payloadDigest,components,consumed,producerInvocation)
                    +java.util.Arrays.hashCode(payload);
        }
    }
    public record Event(long sequence,String kind,Instant time,String detail) {}
    public RunSnapshot { invocations=List.copyOf(invocations);values=List.copyOf(values);events=List.copyOf(events); }
    public boolean terminal() { return status!=Status.ACTIVE; }
}
