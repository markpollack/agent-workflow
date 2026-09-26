package io.github.markpollack.workflow.batch.durable;

import java.time.Instant;
import java.util.*;

/** Store format, kept separate from both compiler analysis and public inspection. */
final class RunState {
    public int format=3;
    public String id,key,admission,compatibility,display,authored,deadlineOrigin;
    public ApplicationDeployment.Manifest deployment;
    public long admitted,deadline,generation,leaseUntil,leaseMillis;
    public String owner="",status="ACTIVE",reasonCode="",reasonMessage="",actor="",output="";
    public long terminalAt;
    public int next,maximumDeliveries;
    public String rootId="",parentId="",parentInvocation="";
    public int depth,descendants,maximumChildDepth,maximumDescendants;
    public Map<String,Value> values=new LinkedHashMap<>();
    public List<Invocation> invocations=new ArrayList<>();
    public List<Event> events=new ArrayList<>();

    public static final class Value {
        public String id,type,shape,codec,digest,producer;
        public String sourceRun="",sourceValue="";
        public byte[] payload;
        public List<String> components=new ArrayList<>(),consumed=new ArrayList<>();
        RunSnapshot.Value snapshot() { return new RunSnapshot.Value(id,type,shape,codec,digest,payload,components,consumed,producer,sourceRun,sourceValue); }
    }
    public static final class Invocation {
        public String id,placement,input,output,status="UNRESOLVED";
        public String childId="",childOutcome="",childReason="";
        public long settledAt;
        public List<Delivery> deliveries=new ArrayList<>();
        RunSnapshot.Invocation snapshot() { return new RunSnapshot.Invocation(id,placement,input,output,status,
                deliveries.stream().map(Delivery::snapshot).toList(),childId,childOutcome,childReason,
                settledAt==0?null:Instant.ofEpochMilli(settledAt)); }
    }
    public static final class Delivery {
        public String id,disposition="UNRESOLVED";
        public int number;
        public long generation,charged;
        RunSnapshot.Delivery snapshot() { return new RunSnapshot.Delivery(id,number,generation,Instant.ofEpochMilli(charged),disposition); }
    }
    public static final class Event {
        public long sequence,time;
        public String kind,detail;
        RunSnapshot.Event snapshot() { return new RunSnapshot.Event(sequence,kind,Instant.ofEpochMilli(time),detail); }
    }
    void event(String kind,long time,String detail) {
        Event e=new Event();e.sequence=events.size()+1L;e.time=time;e.kind=kind;e.detail=detail;events.add(e);
    }
    boolean active() { return status.equals("ACTIVE"); }
    void terminal(String outcome,String code,String message,String actor,long now) {
        if(!active()) return;
        status=outcome;reasonCode=code;reasonMessage=message;this.actor=actor;terminalAt=now;
        generation=Math.incrementExact(generation);owner="";leaseUntil=0;
        for(Invocation call:invocations) if(call.status.equals("UNRESOLVED")) {
            call.status=outcome;
            for(Delivery delivery:call.deliveries) if(delivery.disposition.equals("UNRESOLVED")) delivery.disposition=code;
        }
        event(outcome,now,code+":"+message);
    }
    RunSnapshot snapshot() {
        return new RunSnapshot(id,display,RunSnapshot.Status.valueOf(status),Instant.ofEpochMilli(admitted),
                Instant.ofEpochMilli(deadline),deadlineOrigin,authored,deployment,generation,owner,
                leaseUntil==0?null:Instant.ofEpochMilli(leaseUntil),next,
                active()?null:new RunSnapshot.Reason(reasonCode,reasonMessage,actor,Instant.ofEpochMilli(terminalAt)),
                invocations.stream().map(Invocation::snapshot).toList(),values.values().stream().map(Value::snapshot).toList(),
                events.stream().map(Event::snapshot).toList(),new RunSnapshot.Lineage(rootId,parentId,parentInvocation,
                depth,descendants,maximumChildDepth,maximumDescendants));
    }
}
