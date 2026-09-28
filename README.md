# Agent Workflow

Typed Java steps with compiler-validated bindings and durable local execution. The application supplies its step objects and dependencies. The runtime saves exact inputs, results and progress in an embedded H2 database.

The executable workflow path in this checkout supports sequential steps and explicit terminal outcomes. The compiler also contains structural analysis for broader compositions; those constructs are refused by the durable runtime.

## Define and execute

Implement `Step<I,O>` with concrete input/output types. Constructors may take application dependencies:

```java
final class Greet implements Step<Request, Greeting> {
    private final GreetingService service;
    Greet(GreetingService service) { this.service = service; }

    public Greeting execute(StepContext context, Request input) {
        return service.greet(input.customer());
    }
}
```

Register supplied objects, compile the definition, then open one runtime for the database:

```java
var deployment = new ApplicationDeployment("greetings", "build-17",
        Map.of("prefix", "Hello, "),
        Map.of("greet", new Greet(service), "receipt", receiptStep));
var workflow = deployment.define("greeting-receipt")
        .then("greet").then("receipt").terminate(Terminal.SUCCEEDED).build();
try (var runtime = DurableWorkflows.open(database, deployment)) {
    var run = runtime.start(workflow, "customer-17", new Request("Ada"));
    var completed = runtime.resume(run.runId(), workflow);
    System.out.println(runtime.result(completed.runId(), workflow));
}
```

See the complete, compiling [SequentialRecoveryExample](workflow-batch/src/main/java/io/github/markpollack/workflow/batch/examples/SequentialRecoveryExample.java) for imports, service implementation, registration and a process-death demonstration.

`StepContext` carries run, logical invocation and physical attempt identities, deadline and declared configuration. Business results travel through typed return values and compiler-derived inputs.

## Execution and recovery

`resume` runs directly on its calling Java thread until terminal completion or an application interrupt. `advance` executes at most one step. Neither hands work to an executor. Different caller threads can advance different runs concurrently; a competing call for the same run refuses before charging another attempt. Supplied steps and their dependencies must support whatever concurrency the application uses.

One runtime owns a canonical database path until shutdown or process death. There are no renewable run leases or runtime scheduling threads. The embedded database may own storage-maintenance threads. `shutdown(Duration)` rejects new calls, drains existing calls and returns false if the timeout expires; incomplete shutdown retains database ownership. The runtime never closes supplied dependencies or interrupts application code to force drainage.

Progress and outcomes commit atomically, with application code outside transactions. A compatible application can reopen after process death and `resume` an unfinished run. Committed steps are reused. A charged attempt with no committed outcome may execute again within its finite attempt allowance, so external effects need application-level idempotency. Known step failures are terminal; `resume` does not repair or restart a terminal run. Cancellation and deadline expiry prevent further accepted results but do not stop already running application code.

The declared build and configuration identify compatibility; the runtime does not archive executable code or dependency objects. Existing incompatible store formats refuse without migration.

## Run the recovery example

Requires Java 21 or later. From this checkout:

```bash
./mvnw -q -pl workflow-batch -am install -DskipTests
./mvnw -q -pl workflow-batch dependency:build-classpath -Dmdep.outputFile=target/example-classpath.txt
java -cp "workflow-batch/target/classes:$(cat workflow-batch/target/example-classpath.txt)" \
  io.github.markpollack.workflow.batch.examples.SequentialRecoveryExample \
  /tmp/workflow-example pause-after-first
```

After `READY`, kill that Java PID with `kill -9 <pid>`. Run the same Java command with `recover` in place of `pause-after-first`. It reports saved progress at step 1 and executes only the receipt step. `greet.calls` contains one entry. The pause belongs to the demonstration application; it is not a durable workflow timer.

The automated `SequentialExampleIT` performs the kill, rejects a second live owner, and verifies recovery in another JVM. `ProcessRecoveryIT` exercises transaction and application-return crash boundaries.

## Modules

| Module | Responsibility |
|--------|----------------|
| `workflow-flows` | Step/StepContext, typed workflow compiler and leaf step adapters |
| `workflow-batch` | Durable local runtime, JDBC progress store and recovery example |
| `workflow-journal` | Journal recording support |
| `workflow-api`, `workflow-core` | Agent APIs and lower-level computation patterns |
| `workflow-tools`, `workflow-agents` | Agent tools and implementations |
| `workflow-examples` | Additional agent examples |

## Verification

```bash
./mvnw verify
```

## License

Business Source License 1.1 — see [LICENSE](LICENSE).
