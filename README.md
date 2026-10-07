# Agent Workflow

Agent Workflow is a Java library for composing application services and AI-powered steps into readable, durable workflows.

Write each step as an ordinary Java object with typed inputs, typed outputs and constructor-injected dependencies. Describe the workflow with a fluent Java DSL. The engine connects compatible values, validates the definition before execution, and saves progress so completed work can survive a process restart.

The design brings familiar batch-processing ideas—steps, execution records and saved progress—to workflows that include agent calls, evaluation and decisions.

## Readable control flow, explicit data flow

A workflow should make its sequence and choices easy to see. Connecting its data should not require string-keyed context lookups or repetitive mapping functions.

Agent Workflow derives step inputs from declared Java types and the values available along the workflow's execution paths. A step can consume a preceding result, an earlier available result, or a domain record combining several required values. When those values do not identify a unique valid input, validation rejects the definition rather than guessing. These checks happen when the workflow is built and validated; `javac` alone does not check the entire workflow.

Business data travels through inputs and return values. `StepContext` carries execution metadata and the saved deadline. Application dependencies arrive through constructors.

The broader R1 DSL is intended to express decisions, verdict-based routing, parallel branches, bounded iteration, reusable workflows and durable waits while keeping these data-flow rules consistent.

## Current development status

R1 is under development and unreleased. This checkout supports durable Steps, nested reusable workflows within one run, exhaustive enum decisions, native Verdict routing, and explicit terminal outcomes. Parallelism, loops and waits remain planned work.

The examples below describe supported execution. The public DSL selects supplied Step objects; registration names give those objects stable deployment identities.

## Reuse earlier values

The existing [saved-input recovery fixture](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/durable/KernelFixtures.java) runs five steps: `first → second → third → fourth → fifth`. Two input records express the data needed beyond the immediately preceding result:

```java
public record SecondInput(First first, Request original) {}
public record FifthInput(Fourth latest, SecondInput earlier) {}
```

| Step | Declared input → output |
|------|-------------------------|
| `first` | `Request → First` |
| `second` | `SecondInput → Second` |
| `third` | `Second → Third` |
| `fourth` | `Third → Fourth` |
| `fifth` | `FifthInput → Reply` |

For `second`, the engine combines the first result with the original request. For `fifth`, it combines the latest result with the **exact input previously supplied to `second`**. The author declares those record types; no context keys or mapping functions connect them.

That earlier input is a saved value. Recovery reuses it instead of rebuilding it from newer data. [ProcessRecoveryIT](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/durable/ProcessRecoveryIT.java) exercises a stricter variant that produces newer `Request` and `First` values before the final step. After killing and reopening the JVM, it verifies that the final step still receives the original saved `SecondInput`.

## Define and execute

Implement `Step<I,O>` with concrete input/output types. Constructors may take application dependencies. These excerpts omit imports and domain/service declarations; the complete runnable example is linked below:

```java
final class Greet implements Step<Request, Greeting> {
    private final GreetingService service;
    Greet(GreetingService service) { this.service = service; }

    public Greeting execute(StepContext context, Request input) {
        return service.greet(input.customer(), context);
    }
}
```

Register supplied objects, build and validate the workflow, then open one runtime for the database:

```java
var greet = new Greet(service);
var steps = StepRegistry.of(Map.of("greet", greet, "receipt", receiptStep));
var compatibility = new ExecutionCompatibility("greetings", "build-17",
        Map.of("prefix", settings.greetingPrefix()));
var workflow = Workflows.define("greeting-receipt")
        .then("greet", greet).then("receipt", receiptStep)
        .terminate(Terminal.SUCCEEDED).build();
try (var runtime = DurableWorkflows.open(database, steps, compatibility)) {
    var run = runtime.start(workflow, "customer-17", new Request("Ada"));
    var completed = runtime.resume(run.runId(), workflow);
    System.out.println(runtime.result(completed.runId(), workflow));
}
```

See the complete, compiling [SequentialRecoveryExample](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/SequentialRecoveryExample.java) for imports, service implementation, registration and a process-death demonstration.

Start with the [simple Spring example](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/SimpleGreetingWorkflowTest.java): two distinct Step beans, constructor-injected settings, a workflow bean and automatic registration from Spring's named Step map. The [advanced Spring example](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/GreetingWorkflowTest.java) separately covers two configured instances of the same type, repeated use, an unused bean and recovery through a fresh context.

Spring qualifiers select a bean when injection by type is ambiguous. DSL labels name authored positions, not bean lookup requests. Unlabeled calls generate positional labels; identity is not guaranteed stable across workflow edits. Both examples use `@Configuration(proxyBeanMethods = false)` with factory-method parameter injection and ordinary singleton scope. This configuration-class choice is separate from generic contract checking for proxies of Step implementations.

The immutable `WorkflowGraph` determines execution through its entry node and transitions. Stored node/binding order and registry order do not determine traversal. Explicit FAILED and CANCELLED terminals require a reason.

`StepContext` identifies the run, logical step invocation and physical execution attempt. Business inputs are resolved from the validated workflow definition.

## Exhaustive decisions and native assessments

An ordinary decision returns a concrete Java enum. Every constant must have exactly one arm. `end()` closes that choice; continuing arms converge on compiler-derived captures. Terminal arms need no later continuation. Arms may contain further decisions and reusable workflows. Branch-local values remain isolated until a checked convergence makes a value available.

```java
var workflow = Workflows.define("render")
        .decision("destination", choose)
        .when(Destination.SHORT).then(shortForm)
        .when(Destination.FULL).then(fullForm).end()
        .then(publish).terminate(Terminal.SUCCEEDED).build();
```

For native assessments, supply a `Step<Subject,Verdict>` that configures and invokes the current Agent Judge `Jury` for the subject. The runtime validates the returned Verdict with `requireUsable()` and routes using its native `conclusion()`. Declare all four conclusions:

```java
var workflow = Workflows.define("explain-assessment")
        .verdict("quality", assess)
        .when(PASS).then(explain)
        .when(FAIL).then(explain)
        .when(INCONCLUSIVE).then(explain)
        .when(NOT_APPLICABLE).then(explain).end()
        .terminate(Terminal.SUCCEEDED).build();
```

The imports for these constants are `Verdict.Conclusion.*`. An arm can request a record such as `Explanation(Subject subject, Verdict assessment, Verdict.Conclusion conclusion)`; the compiler supplies the original subject, complete assessment and accepted conclusion. Native ERROR and ABSTAIN judgments can both conclude INCONCLUSIVE, while their distinct judgments, reasoning, errors, invocation facts and composition evidence remain in the assessment. Application reliance or score policy is separate from this routing contract. A thrown assessment call fails with `ASSESSMENT_FAILED`; an unusable returned Verdict fails with `ASSESSMENT_INVALID`. Neither failure manufactures an INCONCLUSIVE assessment.

Workflow accepts the assessment, selected route and exact initial arm input in one store transaction before executing the arm. Convergence captures retain the selected source and exact payload when the arm completes. Recovery uses those saved facts without repeating the accepted assessment or deriving its route again. Unaccepted deliveries may repeat after a crash, so external effects still need application idempotency.

See [DecisionRecoveryExample](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/DecisionRecoveryExample.java) for a complete compiling application using a current native jury, ordinary decisions, injected settings and durable execution. [DecisionRecoveryIT](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/durable/DecisionRecoveryIT.java) kills and replaces JVMs around assessment, arm and capture commits.

[PrReviewDecisionExample](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/PrReviewDecisionExample.java) is a complete PR-review-style application: fetch a diff, retain the native assessment alongside the original PR and revision, route all four conclusions, choose a report format, and reopen after assessment acceptance without repeating the jury. It uses deterministic PR data and judge observations with the real native jury and durable store; it makes no provider or GitHub call. Run its `main` on the workflow-batch test classpath with a fresh store directory as the first argument.

Native routing uses Agent Judge `0.18.0-SNAPSHOT`, `agent-judge-core` and `agent-judge-json-jackson2`, with the producer's strict version-6 Verdict codec. These dependencies are optional for applications using only ordinary workflows; native applications must include the JSON artifact. The codec supports the producer's registered built-in requirement and voting-rule forms and refuses unsupported custom forms rather than discarding evidence. Native artifact fingerprints participate in compatibility, so changing snapshot bytes requires a new compatible deployment decision. Store format 7 deliberately refuses older databases; no automatic migration is supplied.

## Reuse a workflow within one run

A composite uses the same runtime and deployment as its caller. Its inner values are private; its successful result returns through the call's typed boundary. Reusing a definition twice creates two durable scopes without creating separate runs:

```java
var poll = Workflows.define("poll")
        .then("fetch", fetch)
        .then("assess", assess)
        .terminate(Terminal.SUCCEEDED).build();
var index = Workflows.define("index")
        .subWorkflow("before-index", poll)
        .then("build-index", buildIndex)
        .subWorkflow("after-index", poll)
        .then("report", report)
        .terminate(Terminal.SUCCEEDED).build();
```

Here `fetch` takes a `JobHandle`, `assess` returns `JobStatus`, and `buildIndex` produces a new `JobHandle`. The second poll receives that new handle; `report` receives the second poll's status. See the complete [standalone example](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/CompositeRecoveryExample.java) and [Spring example with fresh-context recovery](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/CompositeWorkflowTest.java). A definition can itself call another definition through the same method. Recursion is refused, and admitted depth/work limits are finite and configurable.

Two definitions with the same authored name and shape may use different configured Step instances. Give those instances distinct canonical registration names and declare their configuration in `ExecutionCompatibility`; preparation preserves each call's selected definition and beans. A restarted application can rebuild equivalent definitions with fresh objects under those same names.

The [configured nested example](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/ConfiguredCompositeExample.java)
uses two same-named poll definitions with different supplied fetch objects. Its
[Spring recovery test](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/examples/ConfiguredCompositeWorkflowTest.java)
shows explicit bean qualifiers and fresh-context reuse through two levels of composites.

`RunSnapshot.scopes()` shows inner progress, local outcomes, returns and revocations. A committed local outcome survives its own deadline, while a cancelled or expired enclosing scope can still prevent its return. See the [scope and recovery guide](workflow-batch/README-durable.md#composite-scopes-and-return) for those boundaries. The [composite process tests](workflow-batch/src/test/java/io/github/markpollack/workflow/batch/durable/CompositeRecoveryIT.java) kill separate JVMs at entry, result and return boundaries.

## Execution and recovery

`resume` executes steps directly on the calling Java thread until the run finishes or an application interrupt stops continuation. `advance` executes at most one Step, composite entry or composite return. Neither hands work to an executor. Different caller threads can execute different runs concurrently; competing execution of the same run refuses. One local runtime owns the database.

Progress and outcomes commit atomically, with `Step.execute` outside persistence transactions. After process death, a compatible application can reopen and resume unfinished work using committed results. An attempt whose outcome was not committed may execute again within its finite allowance, so external effects need application-level idempotency.

Crash recovery is distinct from retrying a known failure or restarting a terminal run. Known step failures are currently terminal; `resume` does not reopen them. Operator restart from saved progress after terminal failure remains an open product decision. Cancellation and deadline expiry prevent further accepted results but do not stop already running application code.

See [runtime lifecycle and recovery details](workflow-batch/README-durable.md) for shutdown, concurrency, database paths, compatibility and storage limits.

## Run the recovery example

Requires Java 21 or later. The runnable example lives in test sources and is excluded from the published library JAR. In IntelliJ, run its `main` using the workflow-batch test classpath. Use a fresh directory for each demonstration and recover before the saved deadline (one hour by default). From this checkout:

```bash
./mvnw -q -pl workflow-batch -am install -DskipTests
./mvnw -q -pl workflow-batch dependency:build-classpath -Dmdep.outputFile=target/example-classpath.txt
java -cp "workflow-batch/target/test-classes:workflow-batch/target/classes:$(cat workflow-batch/target/example-classpath.txt)" \
  io.github.markpollack.workflow.batch.examples.SequentialRecoveryExample \
  /tmp/workflow-example pause-after-first
```

After `READY`, kill that Java PID with `kill -9 <pid>`. Run the same Java command with `recover` in place of `pause-after-first`. It reports saved progress at step 1 and executes only the receipt step. `greet.calls` contains one entry. The pause belongs to the demonstration application; it is not a durable workflow timer.

The automated `SequentialExampleIT` performs the kill, rejects a second live owner, and verifies recovery in another JVM. `ProcessRecoveryIT` exercises transaction and application-return crash boundaries.

## Modules

| Module | Responsibility |
|--------|----------------|
| `workflow-flows` | Step/StepContext, workflow validation and input binding, and leaf step adapters |
| `workflow-batch` | Durable local runtime and JDBC progress store; recovery example in test sources |
| `workflow-journal` | Journal recording support |
| `workflow-api`, `workflow-core` | Agent APIs and lower-level computation patterns |
| `workflow-tools`, `workflow-agents` | Agent tools and implementations |
| `workflow-examples` | Additional agent examples |

The Journal recorder is available for explicitly submitted observations; it is not yet connected to this durable runtime. The runtime's JDBC store owns checkpoints and recovery.

## Verification

```bash
./mvnw verify
```

## License

Business Source License 1.1 — see [LICENSE](LICENSE).
