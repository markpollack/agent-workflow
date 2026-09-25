package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;

/** Separate JVM entry used only by the process-loss integration tests. */
public final class ProcessWorker {
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);String mode=args[1],boundary=args[2];int occurrence=Integer.parseInt(args[3]);
        Files.createDirectories(directory);Path database=directory.resolve("runs");
        var deployment=deployment(mode.equals("mismatch")?"fixture-v2":"fixture-v1",Map.of("evidence",directory.toString()));var workflow=o01(deployment,true);
        var policy=new ExecutionPolicy(Duration.ofSeconds(2),3);
        AtomicInteger seen=new AtomicInteger();ObjectMapper mapper=new ObjectMapper();
        BoundaryHooks hooks=(point,run)->{
            if(mode.equals("crash")&&point.equals(boundary)&&seen.incrementAndGet()==occurrence) {
                try {
                    Path temporary=directory.resolve("kill-point.tmp");
                    Files.writeString(temporary,mapper.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),"boundary",point,"occurrence",occurrence,"runId",run)));
                    Files.move(temporary,directory.resolve("kill-point.json"),StandardCopyOption.ATOMIC_MOVE);
                    System.out.println("KILL_POINT pid="+ProcessHandle.current().pid()+" boundary="+point+" run="+run);System.out.flush();
                    while(true) java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
                } catch(Exception ex) { throw new IllegalStateException(ex); }
            }
        };
        try(var runtime=new DurableWorkflows(database,deployment,policy,hooks)) {
            System.out.println("PROCESS pid="+ProcessHandle.current().pid()+" mode="+mode);System.out.flush();
            if(mode.equals("mismatch")) {
                String id=Files.readString(directory.resolve("run-id.txt"));
                Files.writeString(directory.resolve("mismatch-before.json"),StoreTestSupport.state(database,id));
                Map<String,String> refusals=new LinkedHashMap<>();
                try { runtime.resume(id,workflow);throw new AssertionError("mismatch resumed"); }
                catch(WorkflowRefusal ex) {refusals.put("resume",ex.code());}
                try { runtime.result(id,workflow);throw new AssertionError("mismatch decoded result"); }
                catch(WorkflowRefusal ex) {refusals.put("result",ex.code());}
                try { runtime.start(workflow,"process-o01",new Request("original"));throw new AssertionError("mismatch reused admission"); }
                catch(WorkflowRefusal ex) {refusals.put("start",ex.code());}
                Files.writeString(directory.resolve("mismatch-after.json"),StoreTestSupport.state(database,id));
                Files.writeString(directory.resolve("mismatch-result.json"),mapper.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),"refusals",refusals)));
                return;
            }
            var discovered=runtime.discover();
            Files.writeString(directory.resolve(mode+"-discovery.json"),mapper.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),
                    "runs",discovered.stream().map(RunSnapshot::runId).toList())));
            var run=runtime.start(workflow,"process-o01",new Request("original"));
            Files.writeString(directory.resolve("run-id.txt"),run.runId());
            Files.writeString(directory.resolve(mode+"-before.json"),StoreTestSupport.state(database,run.runId()));
            if(mode.equals("admit")) return;
            while(run.leaseUntil()!=null&&Instant.now().isBefore(run.leaseUntil())) { Thread.sleep(10);run=runtime.inspect(run.runId()); }
            var result=runtime.resume(run.runId(),workflow,"process-"+ProcessHandle.current().pid());
            Files.writeString(directory.resolve(mode+"-after.json"),StoreTestSupport.state(database,run.runId()));
            Files.writeString(directory.resolve(mode+"-result.json"),mapper.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),"status",result.status().name(),
                    "result",runtime.result(run.runId(),workflow).toString())));
            if(result.status()!=RunSnapshot.Status.SUCCEEDED) throw new AssertionError(result);
        }
    }
}
