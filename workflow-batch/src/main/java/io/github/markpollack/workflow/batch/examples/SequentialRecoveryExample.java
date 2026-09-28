package io.github.markpollack.workflow.batch.examples;

import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import io.github.markpollack.workflow.batch.durable.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.Terminal;

/** A supplied service, two concrete typed steps and process recovery using saved progress. */
public final class SequentialRecoveryExample {
    private SequentialRecoveryExample() {}
    public record Request(String customer) {}
    public record Greeting(String text) {}
    public record Receipt(String text) {}

    /** An ordinary application dependency: never serialized or constructed by the runtime. */
    static final class GreetingService {
        private final String prefix;
        private final Path evidence;
        GreetingService(String prefix,Path evidence) {this.prefix=prefix;this.evidence=evidence;}
        Greeting greet(String customer,StepContext context) throws Exception {
            Files.writeString(evidence,context.runId()+" "+context.invocationId()+" "+context.attemptId()+"\n",
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            return new Greeting(prefix+customer);
        }
    }
    static final class Greet implements Step<Request,Greeting> {
        private final GreetingService service;
        Greet(GreetingService service) {this.service=service;}
        public Greeting execute(StepContext context,Request input) throws Exception {
            report("greet",context);return service.greet(input.customer(),context);
        }
    }
    static final class PrintReceipt implements Step<Greeting,Receipt> {
        private final String template;
        PrintReceipt(String template) {this.template=template;}
        public Receipt execute(StepContext context,Greeting input) {
            report("receipt",context);return new Receipt(template.formatted(input.text()));
        }
    }
    private static void report(String step,StepContext context) {
        System.out.printf("STEP %s thread=%s run=%s invocation=%s attempt=%s%n",step,
                Thread.currentThread().getName(),context.runId(),context.invocationId(),context.attemptId());
    }

    /**
     * Arguments: directory and either run, pause-after-first, or recover.
     * Kill the pause-after-first JVM after its READY marker, then run recover on the same directory.
     * The application deliberately blocks main at that marker for the demonstration; it is not a workflow timer.
     */
    public static void main(String[] args) throws Exception {
        if(args.length!=2 || !java.util.Set.of("run","pause-after-first","recover").contains(args[1]))
            throw new IllegalArgumentException("expected: <directory> <run|pause-after-first|recover>");
        Path directory=Path.of(args[0]).toAbsolutePath().normalize();Files.createDirectories(directory);
        String mode=args[1];
        var service=new GreetingService("Hello, ",directory.resolve("greet.calls"));
        var deployment=new ApplicationDeployment("sequential-example","example-v1",
                Map.of("greetingPrefix","Hello, ","receiptTemplate","Receipt: %s","evidenceDirectory",directory.toString()),
                Map.of("greet",new Greet(service),"receipt",new PrintReceipt("Receipt: %s")));
        var workflow=deployment.define("greeting-receipt")
                .then("greet").then("receipt").terminate(Terminal.SUCCEEDED).build();
        System.out.printf("PROCESS pid=%d thread=%s mode=%s%n",ProcessHandle.current().pid(),Thread.currentThread().getName(),mode);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            var admitted=runtime.start(workflow,"customer-17",new Request("Ada"));
            System.out.printf("SAVED run=%s next=%d status=%s%n",admitted.runId(),admitted.nextOperation(),admitted.status());
            if(mode.equals("pause-after-first")) {
                var progress=runtime.advance(admitted.runId(),workflow);
                Files.writeString(directory.resolve("ready.txt"),ProcessHandle.current().pid()+" "+admitted.runId()+" "+progress.nextOperation());
                System.out.printf("READY pid=%d next=%d; kill this process, then run recover%n",ProcessHandle.current().pid(),progress.nextOperation());
                System.out.flush();new CountDownLatch(1).await();
            }
            var completed=runtime.resume(admitted.runId(),workflow);
            System.out.printf("RESULT run=%s status=%s value=%s%n",completed.runId(),completed.status(),runtime.result(completed.runId(),workflow));
        }
    }
}
