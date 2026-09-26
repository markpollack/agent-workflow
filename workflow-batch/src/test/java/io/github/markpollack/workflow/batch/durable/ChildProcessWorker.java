package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;
import static io.github.markpollack.workflow.batch.durable.ChildFixtures.*;

/** Production lifecycle in a JVM that the integration test kills at a durable boundary. */
public final class ChildProcessWorker {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);String mode=args[1],boundary=args[2];Files.createDirectories(directory);
        Path file=directory.resolve("runs");ObjectMapper mapper=new ObjectMapper();
        var deployment=deployment(Map.of("evidence",directory.toString()));
        var built=repeated(deployment,1,Echo.class,Terminal.SUCCEEDED,DURATION,DURATION);
        BoundaryHooks hooks=(point,id)->{
            if(mode.equals("crash")&&point.equals(boundary)) try {
                Path temporary=directory.resolve("kill.tmp");
                Files.writeString(temporary,mapper.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),"boundary",point,"runId",id)));
                Files.move(temporary,directory.resolve("kill.json"),StandardCopyOption.ATOMIC_MOVE);
                while(true) java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
            } catch(Exception ex) {throw new IllegalStateException(ex);}
        };
        try(var runtime=new DurableWorkflows(file,deployment,new ExecutionPolicy(Duration.ofSeconds(1),3),hooks)) {
            var discovery=runtime.discover();
            Files.writeString(directory.resolve(mode+"-discovery.json"),mapper.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),
                    "runs",discovery.stream().map(RunSnapshot::runId).toList())));
            var root=runtime.start(built.parent(),"child-process",new Request("exact-original"));
            Files.writeString(directory.resolve("parent-id.txt"),root.runId());
            while(runtime.discover().stream().anyMatch(r->r.leaseUntil()!=null&&r.leaseUntil().isAfter(Instant.now()))) Thread.sleep(10);
            Files.writeString(directory.resolve(mode+"-before.json"),mapper.writeValueAsString(states(file)));
            var result=runtime.resume(root.runId(),built.parent(),"process-"+ProcessHandle.current().pid());
            Files.writeString(directory.resolve(mode+"-after.json"),mapper.writeValueAsString(states(file)));
            Files.writeString(directory.resolve(mode+"-result.json"),mapper.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),
                    "status",result.status().name(),"value",runtime.result(root.runId(),built.parent()))));
            if(result.status()!=RunSnapshot.Status.SUCCEEDED) throw new AssertionError(result);
        }
    }
    private static Map<String,JsonNode> states(Path file) throws Exception {
        Map<String,JsonNode> result=new TreeMap<>();ObjectMapper mapper=new ObjectMapper();
        try(var c=StoreTestSupport.connect(file);var s=c.createStatement();var rows=s.executeQuery("SELECT id,state FROM aw_run")) {
            while(rows.next()) result.put(rows.getString(1),mapper.readTree(rows.getString(2)));
        }
        return result;
    }
}
