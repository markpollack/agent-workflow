# Local runtime lifecycle and recovery

This developer guide describes the current sequential runtime. Start with the [project README](../README.md) for workflow authoring and the runnable recovery example.

[`DurableWorkflows`](src/main/java/io/github/markpollack/workflow/batch/durable/DurableWorkflows.java) accepts immutable `ValidatedWorkflow` definitions containing sequential steps and explicit terminal outcomes. Decisions, verdict routing, parallel groups, fan-out, loops, timers and reusable workflow composition are not executable on this path yet. Submitting a definition with unsupported constructs refuses admission.

## Application-supplied steps

Implement `Step<I,O>` with concrete input/output types and register supplied objects in `StepRegistry`. Constructor dependencies are ordinary application objects, including objects obtained from a DI container. The runtime calls the registered instance directly; it does not require a public no-argument constructor, construct a fresh step per attempt or close its dependencies.

`Workflows.define(name).then(actualStep).terminate(...).build()` builds and validates a sequential workflow. Concrete step declarations supply the types; authors do not write explicit fluent generic arguments. `then(placement, actualStep)` gives repeated uses of one registration distinct authored locations. Names and identity/configuration strings must contain well-formed Unicode; validation does not normalize them.

`StepContext` carries the run ID, stable logical invocation ID, unique physical attempt ID/number, absolute deadline and immutable declared configuration. It is not a business-result map or a live cancellation token. Business inputs come from the validated bindings and saved values.

## Types, validation and invocation

Java erases generic method dispatch, but a concrete class declaration such as `implements Step<Request, List<Reply>>` retains a generic signature. [`StepTypes`](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/compiler/StepTypes.java) reads those signatures, including concrete inherited declarations. `new GenericStep<Request>()` alone does not retain the argument in its runtime class. Raw/unresolved declarations, conflicting proxy contracts and erased lambda classes refuse validation. All inherited Step declarations must agree; proxy interface order cannot select a contract. There are no public `inputType()`/`outputType()` hints to maintain.

`then` selects those full types; `build` validates control structure, input bindings and durable value shapes. The existing [RegionAnalyzer](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/compiler/RegionAnalyzer.java) resolves record components in declaration order and derives captures from typed facts across continuing paths. Captures are analysis of where a value comes from, not reflective invocation or object-field guessing. They do not require type-hint methods. Broader path analysis exists in the validator; this sequential runtime still refuses those composition constructs.

Admission checks the supplied objects and root value against the validated definition. Execution checks the saved definition/deployment and exact input identity, type, codec and provenance before decoding it. [`ResolvedApplication`](src/main/java/io/github/markpollack/workflow/batch/durable/ResolvedApplication.java) compares the actual Step signature with the selected full input/output types. Only then does its direct invocation bridge cast to `Step<Object,Object>` for erased Java dispatch. That cast does not resolve bindings or weaken the preceding checks.

For an assembled record input, the recipe retains component order and source identity. The runtime verifies/decodes each component, invokes the record's canonical constructor in that order, and checks/saves the resulting value. This reflection constructs a business value; it does not construct steps or their dependencies. Constructor failures and unsupported value shapes refuse before step entry.

## Calls and execution threads

| Entry point | Behavior |
|-------------|----------|
| `start` | Atomically admits a run and exact root input; executes no step. Reusing an idempotency key with identical input and compatibility facts returns the existing run. Conflicting reuse refuses. |
| `resume` | Advances the run synchronously until terminal completion or an application interrupt stops continuation; returns a `RunSnapshot`. |
| `advance` | Executes at most one step or engine boundary on the caller; returns saved progress. |
| `inspect` | Returns durable values, provenance, attempts, events and status; observes expiry without executing steps. |
| `discover` | Returns unfinished runs after observing expiry; does not schedule them. |
| `cancel` | Permanently records cancellation if the run is still active; sends no Java interrupt. |
| `result` | Decodes the accepted successful result using the compatible supplied deployment and definition. |

`resume` and `advance` execute `Step.execute` on the calling Java thread with **no executor handoff**. The runtime creates no scheduling or lease-renewal threads. H2 owns its internal storage-maintenance threads. Ordinary application code that blocks inside a step continues to occupy its caller.

[`RuntimeLifecycle`](src/main/java/io/github/markpollack/workflow/batch/durable/RuntimeLifecycle.java) prevents overlapping execution calls for the same run for the duration of the whole call. A competing caller receives `RUN_BUSY` before another attempt is charged. Different runs can execute application code concurrently on different caller threads; supplied steps and dependencies must support that concurrency. Inspection and cancellation can occur while a step is running.

No workflow timers, waiting joins or background resume facility exist here. After a process restart, the application opens the runtime and calls `resume` with the compatible definition. Discovery alone does not continue execution.

## Caller thread and transaction boundaries

```mermaid
sequenceDiagram
    participant Caller
    participant Runtime as DurableWorkflows
    participant Store as JdbcRunStore
    participant Step as Supplied Step
    Caller->>Runtime: advance / resume
    Note over Caller,Runtime: Same caller thread; one guard per run
    Runtime->>Store: TX: verify compatibility + graph prefix
    Runtime->>Store: TX: save exact input + charge attempt
    Runtime->>Store: TX: recheck cancellation / deadline
    Runtime->>Step: execute(context, decoded input)
    Note over Runtime,Step: Outside persistence TX; no executor handoff
    Step-->>Runtime: provisional output
    Runtime->>Runtime: encode and check full value contract
    Runtime->>Store: TX: recheck eligibility; output + successor + terminal + events
    Runtime-->>Caller: committed snapshot
```

Project Reactor and other reactive stacks are not used for scheduling or execution orchestration.

## Atomic progress and exact values

The runtime reads the durable current node, obtains its binding by node ID and follows its one unconditional edge after success. Explicit terminal metadata supplies the final intent and successful value. A shared completion sink exists for structural analysis; the runtime stops at the explicit terminal. It never dispatches by an index into stored nodes or bindings. Recovery verifies that saved invocations form a graph prefix and the cursor points to its first uncommitted node.

The execution sequence separates saved state from application execution:

1. Validate compatibility and resolve the supplied step contracts.
2. In a transaction, select or recover the exact input and charge a physical attempt.
3. In a separate transaction, recheck eligibility immediately before application entry.
4. Call `Step.execute` and encode its returned value outside those transactions.
5. In a transaction, recheck eligibility and atomically accept the output, successor node, attempt disposition and events. If the successor is terminal, its outcome commits in that same transaction.

[`JdbcRunStore`](src/main/java/io/github/markpollack/workflow/batch/durable/JdbcRunStore.java) serializes state transactions across runs through a database transition lock. The lock does not span step execution. Typed decoding and record assembly may occur inside preparation transactions; the outside-transaction guarantee applies to `Step.execute`.

Root inputs, effective step inputs and outputs are immutable snapshots with type/codec identity, payload digest and source provenance. Supported values include records, supported scalars and concrete lists under the fixed strict Jackson contract. Null application values/items, arbitrary POJOs and raw/wildcard generic contracts refuse. Both encoding and decoding check lossless reconstruction.

An earlier assembled input remains its own saved value. A later step can consume it whole; recovery does not rebuild it from newer results. Missing or incompatible committed values refuse recovery. The [saved-input process test](src/test/java/io/github/markpollack/workflow/batch/durable/ProcessRecoveryIT.java) checks this across actual JVM kills.

## Crash recovery, failure and time

A compatible application can reopen after process death and continue unfinished runs. Committed results are reused. A charged attempt without a committed outcome may execute again with its saved input. `ExecutionPolicy.DEFAULT` permits three total physical attempts per logical invocation; exhausting the allowance fails the run. A changed allowance is a compatibility change.

This is not exactly-once external execution. If a process dies after an external effect but before its result commits, recovery can repeat that effect. Use the stable invocation ID for external idempotency where supported. Known step exceptions fail the run immediately; there is no engine retry/backoff facility. `resume` returns a terminal run unchanged and does not provide saved-progress restart after terminal failure. That restart policy remains an open product decision.

The default maximum duration is one hour (`DeadlinePolicy.DEFAULT`, profile `local-v1`). An authored shorter duration tightens it; a longer duration is capped. The lower-level validation API accepts another positive finite `DeadlinePolicy`. Admission saves an absolute deadline and its origin, which recovery never extends.

Each serialized transition samples the database-side wall clock with a persisted high-water mark. At equality with the deadline, success/progress is ineligible. This sample is the transition's eligibility instant, not its later physical fsync time. Backward clock movement does not rewind the saved clock; forward movement can expire runs early. Expiry is observed during runtime calls rather than by a background timer. The first valid terminal transition wins permanently.

Cancellation and expiry reject later result acceptance; neither stops already running application code. A caller already interrupted on entry is refused. If a step returns normally with the interrupt flag set, its outcome is processed, `resume` stops before a successor and the flag is restored after persistence/guard cleanup. An unchecked failure with an `InterruptedException` cause records step failure, unless another terminal outcome already won, and restores the flag. Arbitrary asynchronous interruption of database I/O is not a cancellation mechanism.

## Ownership and shutdown

[`StoreOwnership`](src/main/java/io/github/markpollack/workflow/batch/durable/StoreOwnership.java) holds a process-level file lock for one canonical database path. A second runtime, in this JVM or another, refuses with `OWNER_ACTIVE`. Ownership has no per-run expiry or renewal and cannot be taken over from a live owner. Process death releases the lock so a replacement process can reopen.

Pass the database base path, without H2's `.mv.db` suffix. Parent directories and an existing database symlink are resolved to their real paths before locking. The sidecar is `<base>.workflow-owner.lock`; the held OS lock, not the mere presence of that file, establishes ownership. Semicolons in supplied or resolved paths refuse to prevent JDBC URL options from changing the database target.

`shutdown(Duration)` stops admission of all new calls, including inspection and cancellation, then drains entered calls. It does not stop an entered `resume` after its current step: that call retains its normal continuation rules. Once calls drain, shutdown closes the store and releases ownership. A timeout returns `false`, leaves the runtime closing and retains ownership/resources; call shutdown again after application work returns. Supplied dependencies remain application-owned.

`close()` uses a thirty-second drainage timeout and throws `SHUTDOWN_INCOMPLETE` if calls remain. Shutdown never forcibly interrupts steps or cancels runs. Attempting shutdown from inside an active call on the same thread refuses rather than waiting on itself.

## Deployment compatibility

[`StepRegistry`](src/main/java/io/github/markpollack/workflow/batch/durable/StepRegistry.java) freezes canonical names and supplied object identities. Duplicate names and aliases for one object refuse; unrelated unused entries are allowed. Preparation records exactly the selected node-to-name associations. Fresh objects may recover when these selections and full contracts match.

[`ExecutionCompatibility`](src/main/java/io/github/markpollack/workflow/batch/durable/ExecutionCompatibility.java) copies declared compatibility facts. Application settings are supplied separately to Step constructors from the same settings source. The manifest records application/build identities, the exact configuration digest, fixed codec identity, Jackson core/databind versions and Java runtime version/vendor/VM name. Admission, execution, admission reuse and terminal resume/result access compare supplied compatibility facts against the saved run. Authored definition, selected registrations/types and execution policy also participate in compatibility checks.

The application must assign a new immutable build ID when code, dependencies, codec implementation or relevant deployment specification changes. Even a plausibly compatible build with a different ID refuses recovery. The runtime checks declared identities and observable contracts; it does not prove that dependency bytes or external services are unchanged. Relevant immutable behavior configuration must be explicit. Reusing a build ID for changed code violates this contract and cannot generally be detected.

Steps use the ordinary application class loader. Workflow does not archive executable code or dependency objects, scan dependency graphs, recreate DI containers or switch loaders for recovery. The application supplies the compatible deployment again.

`open(database[, policy])` without a deployment provides exclusive management-only inspection, discovery and cancellation. It does not enable execution or result decoding without compatible steps.

## Store and verification limits

The store uses H2 embedded file mode with `WRITE_DELAY=0` and format-5 run aggregates. Older (including format 4), incomplete, malformed or unknown store formats refuse before writable initialization; there is no checkpoint migration or automatic conversion. Remote workers and simultaneous embedded owners are unsupported. Values and events are retained indefinitely; no pruning API is provided. Filesystem/hardware durability remains within H2's guarantees; JVM-kill tests are not power-loss tests.

Run `./mvnw -pl workflow-batch -am verify` from the project root for unit, consumer and separate-JVM recovery checks. The process harness records PIDs, kill boundaries, recovered facts and external invocation counts under `workflow-batch/target/durable-evidence/`. The full project gate is `./mvnw verify`.

## IDE reading order and examples

1. [Workflows](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/Workflows.java): staged nonempty sequence, actual objects, explicit terminal, shared validation.
2. [ValidatedWorkflow](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/compiler/ValidatedWorkflow.java): owned definition, semantic graph and value contracts. Follow StructuredWorkflowCompiler into RegionAnalyzer, GraphLowering and GraphVerification for binding and topology checks.
3. [WorkflowGraph](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/workflow/WorkflowGraph.java): immutable nodes, edges and per-node binding indexes. Supplied objects live separately on ValidatedWorkflow.
4. StepRegistry and ExecutionCompatibility above, then [ResolvedApplication](src/main/java/io/github/markpollack/workflow/batch/durable/ResolvedApplication.java): preparation freezes selected names/instances and verifies full types without invoking a Step.
5. DurableWorkflows: start, resolve, prepareAdvance, executeAttempt and commitResult. JdbcRunStore owns short transactions and aggregate persistence; RuntimeLifecycle and StoreOwnership own caller exclusion and local store lifetime.

The compiling [standalone example](src/test/java/io/github/markpollack/workflow/batch/examples/SequentialRecoveryExample.java) demonstrates launch, inspection and process recovery. The [Spring test](src/test/java/io/github/markpollack/workflow/batch/examples/GreetingWorkflowTest.java) uses ordinary ExampleSettings and constructor injection, derives compatibility from that same source and takes the canonical Step map from the container. A changed selected configuration refuses recovery before entry. A new business input uses a new launch key without changing application settings.

Recovery means continuing unfinished work after process death. A retry of an unresolved invocation consumes another bounded physical attempt with the exact saved input. Operator restart after terminal failure is a separate unresolved policy. Future scheduler/dashboard integrations can use this launch/management boundary; scheduling, launch-request deduplication beyond the current local idempotency key, same-run composite scopes and independent child runs are not provided by this runtime.
