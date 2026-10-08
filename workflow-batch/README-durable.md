# Local runtime lifecycle and recovery

This developer guide describes the current local runtime. Start with the [project README](../README.md) for workflow authoring and the runnable recovery example.

[`DurableWorkflows`](src/main/java/io/github/markpollack/workflow/batch/durable/DurableWorkflows.java) accepts immutable `ValidatedWorkflow` definitions containing Steps, nested same-run composites, exhaustive decisions/native verdict routing, static parallel groups, bounded runtime fan-out and explicit terminal outcomes. Loops and timers are not executable on this path yet. Submitting a definition with unsupported constructs refuses admission.

## Application-supplied steps

Implement `Step<I,O>` with concrete input/output types and register supplied objects in `StepRegistry`. Constructor dependencies are ordinary application objects, including objects obtained from a DI container. The runtime calls the registered instance directly; it does not require a public no-argument constructor, construct a fresh step per attempt or close its dependencies.

`Workflows.define(name).then(actualStep).terminate(...).build()` builds and validates a sequential workflow. Concrete step declarations supply the types; authors do not write explicit fluent generic arguments. Every `then` creates a distinct authored location, including repeated use of one object. `then(label, actualStep)` optionally names that position; neither it nor the generated positional labels promise identity stability across workflow edits. Names and identity/configuration strings must contain well-formed Unicode; validation does not normalize them.

`StepContext` carries the run ID, stable logical invocation ID, unique physical attempt ID/number, and absolute deadline. Settings and dependencies arrive through Step constructors; compatibility declarations are not a settings service. The context is not a business-result map or a live cancellation token. Business inputs come from the validated bindings and saved values.

## Types, validation and invocation

Java erases generic method dispatch, but a concrete class declaration such as `implements Step<Request, List<Reply>>` retains a generic signature. [`StepTypes`](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/compiler/StepTypes.java) reads those signatures, including concrete inherited declarations. `new GenericStep<Request>()` alone does not retain the argument in its runtime class. Raw/unresolved declarations, conflicting proxy contracts and erased lambda classes refuse validation. All inherited Step declarations must agree; proxy interface order cannot select a contract. There are no public `inputType()`/`outputType()` hints to maintain.

`then` selects those full types; `build` validates control structure, input bindings and durable value shapes. The existing [RegionAnalyzer](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/compiler/RegionAnalyzer.java) resolves record components in declaration order and derives captures from typed facts across continuing paths. Captures are analysis of where a value comes from, not reflective invocation or object-field guessing. They do not require type-hint methods. Broader path analysis exists in the validator; this runtime supports bounded fan-out and still refuses loop and timer constructs.

Admission checks the supplied objects and root value against the validated definition. Execution checks the saved definition/deployment and exact input identity, type, codec and provenance before decoding it. [`WorkflowExecutionBindings`](src/main/java/io/github/markpollack/workflow/batch/durable/WorkflowExecutionBindings.java) compares the actual Step signature with the selected full input/output types. Only then does its direct invocation bridge cast to `Step<Object,Object>` for erased Java dispatch. That cast does not resolve bindings or weaken the preceding checks.

For an assembled record input, the recipe retains component order and source identity. The runtime verifies/decodes each component, invokes the record's canonical constructor in that order, and checks/saves the resulting value. This reflection constructs a business value; it does not construct steps or their dependencies. Constructor failures and unsupported value shapes refuse before step entry.

## Calls and execution threads

| Entry point | Behavior |
|-------------|----------|
| `start` | Atomically admits a run and exact root input; executes no step. Reusing an idempotency key with identical input and compatibility facts returns the existing run. Conflicting reuse refuses. |
| `resume` | Advances the run synchronously until terminal completion or an application interrupt stops continuation; returns a `RunSnapshot`. |
| `advance` | Executes at most one step or engine boundary on the caller; returns saved progress. |
| `inspect` | Returns durable values, provenance, attempts, events and status; persists terminal failure on expiry without executing steps. |
| `discover` | Persists expiry and returns only still-unfinished runs; does not schedule them. |
| `cancel` | Permanently records cancellation if the run is still active; sends no Java interrupt. |
| `result` | Decodes the accepted successful result using the compatible supplied deployment and definition. |

Sequential `resume` and `advance` execute `Step.execute` on the caller. A parallel/fan-out-capable `resume` coordinates saved graph frontiers on its caller and dispatches leaf Steps onto its runtime-owned bounded JDK executor. The caller waits for admitted workers and full settlement. `ExecutionPolicy.maximumConcurrency()` bounds occupied Step slots across the handle, default 4; the executor's finite queue is bounded by that capacity. Graph/group/composite transitions use no worker slot, so nested work progresses at capacity one. H2 owns its internal maintenance threads. A blocked Step occupies its execution slot until it returns/throws; cancellation does not reclaim physical capacity.

A Step cannot recursively call resume/advance on this runtime: use graph composition for nested execution. This prevents an application call from blocking its own finite execution capacity. Supplied Steps/dependencies must support the concurrent uses declared by the workflow.

[`RuntimeLifecycle`](src/main/java/io/github/markpollack/workflow/batch/durable/RuntimeLifecycle.java) prevents overlapping execution calls for the same run for the duration of the whole call. A competing caller receives `RUN_BUSY` before another attempt is charged. Different runs can execute application code concurrently within the shared finite capacity; supplied steps and dependencies must support that concurrency. Inspection and cancellation can occur while a step is running.

Parallel joins persist member readiness and settlement. No workflow timers or background resume facility exist here. After a process restart, the application opens the runtime and calls `resume` with the compatible definition. Discovery alone does not continue execution.

## Execution and transaction boundaries

```mermaid
sequenceDiagram
    participant Caller
    participant Runtime as DurableWorkflows
    participant Store as JdbcRunStore
    participant Step as Supplied Step
    Caller->>Runtime: advance / resume
    Note over Caller,Runtime: Caller coordinates; one guard per run
    Runtime->>Store: TX: verify compatibility + graph prefix
    Runtime->>Store: TX: save exact input + charge attempt
    Runtime->>Store: TX: recheck cancellation / deadline
    Runtime->>Step: execute(context, decoded input)
    Note over Runtime,Step: Outside TX; bounded worker for parallel resume, otherwise caller
    Step-->>Runtime: provisional output
    Runtime->>Runtime: encode and check full value contract
    Runtime->>Store: TX: recheck eligibility; output + successor + terminal + events
    Runtime-->>Caller: committed snapshot
```

Project Reactor and other reactive stacks are not used for scheduling or execution orchestration.

## Atomic progress and exact values

The runtime selects an eligible scope and its saved node progress, obtains that definition's binding by node ID, and follows its unconditional edge after success. Explicit terminal metadata supplies the local intent and successful value. Recovery checks each scope's saved graph prefix, invocation facts and return links. `currentNode()` is a derived root position; `scopes()` exposes the full nested progress. Stored node, binding or registry iteration order does not choose execution.

The execution sequence separates saved state from application execution:

1. Validate compatibility and resolve the supplied step contracts.
2. In a transaction, select or recover the exact input and charge a physical attempt.
3. In a separate transaction, recheck eligibility immediately before application entry.
4. Call `Step.execute` and encode its returned value outside those transactions.
5. In a transaction, recheck eligibility and atomically accept the output, successor node, attempt disposition and events. If the successor is terminal, its outcome commits in that same transaction.

[`JdbcRunStore`](src/main/java/io/github/markpollack/workflow/batch/durable/JdbcRunStore.java) serializes state transactions across runs through a database transition lock. The lock row is shared across runs and released at each commit or rollback. A slow AI call holds no workflow-store lock, so another caller can commit progress on another run. Application-owned transactions are outside this guarantee. Typed decoding and record assembly may occur inside preparation transactions; the outside-transaction guarantee applies to `Step.execute`.

Root inputs, effective step inputs and outputs are immutable snapshots with type/codec identity, payload digest and source provenance. Supported values include records, supported scalars and concrete lists under the fixed strict Jackson contract. Null application values/items, arbitrary POJOs and raw/wildcard generic contracts refuse. Both encoding and decoding check lossless reconstruction.

An earlier assembled input remains its own saved value. A later step can consume it whole; recovery does not rebuild it from newer results. Missing or incompatible committed values refuse recovery. The [saved-input process test](src/test/java/io/github/markpollack/workflow/batch/durable/ProcessRecoveryIT.java) checks this across actual JVM kills.

## Composite scopes and return

`subWorkflow(label, validatedDefinition)` places a typed call to immutable definition data. Each occurrence has a private scope and exact call input within the original run. Nested calls use the same Step preparation, codec, bounded attempt and result-acceptance methods as root Steps. There is no separate child runtime or child-run management API.

Preparation distinguishes authored behavior from implementation selection. A prepared definition includes its authored identity, local node-to-registration selections and local call-to-callee selections. Thus two definitions named `poll` with identical topology can select `fetch-fast` and `fetch-careful` independently. Runtime lookup uses the scope's prepared definition and local node together. Rebuilt equivalent definitions and fresh compatible objects reproduce those selections. Unused registered beans remain legal; swapped callee selections refuse recovery.

| Inspection fact | Meaning |
|---|---|
| `definitions()` | Prepared definition IDs with readable local Step and callee selections |
| `scopes()` | Parent/opening call, exact input, effective deadline, lifecycle and per-node progress |
| `localOutcome()` | Immutable locally accepted success/failure/cancellation, cause, source and result |
| `revocation()` | An ancestor denied further execution or return; accepted inner outcomes remain visible |
| `returns()` | Unique accepted return receipt linking a caller invocation to its child's outcome |
| `resources()` | Admitted depth/work/attempt policy and conservative structural bounds |

A scope is `OPEN`, `LOCAL_TERMINAL`, `RETURNED` or `REVOKED`. An open scope has one frontier node: `READY`, `ENTERED`, `WAITING_CHILD` or `WAITING_GROUP`. A group has an isolated scope for each member, each with its own checked frontier. Settled nodes retain their invocation or terminal reference. A waiting caller must have exactly one checked child that can execute, recover or return. Unexplained missing progress refuses with `PROGRESS_INVALID` or `STRANDED_PROGRESS`; it is never an ordinary durable wait.

Composite entry saves the caller's exact input, private child input, deadline and return continuation in one transaction, without charging a Step attempt. When a final inner Step succeeds, its result and directly reached terminal outcome commit together. Returning that outcome to the caller uses a separate transaction, which saves the boundary value, unique return receipt and caller successor. If that successor is terminal, its outcome joins the return transaction; propagation to its own caller still waits for another transition.

For example, an inner completion accepted at time 90 with deadline 100 can return at time 140 when its parent deadline is 200. Its accepted outcome no longer expires. If the parent instead expires or is cancelled before return, the inner outcome remains visible and return is revoked. A nonfinal leaf result provides no such completion guarantee. An authored inner FAILED/CANCELLED outcome or known Step failure propagates as parent failure, retaining the cause; it does not become external root cancellation.

`ExecutionPolicy.DEFAULT` allows depth 32 (root depth zero) and 10,000 logical invocations. Configure them with `new ExecutionPolicy(maximumAttempts, maximumDepth, maximumInvocations)`. Each leaf and composite occurrence consumes one logical charge, even when a definition is reused; physical reattempts and returns do not charge it again. Summaries are memoized by definition and added for every call occurrence, with checked arithmetic. These defaults are protective bounds, not tested throughput or storage-capacity claims.

The [standalone composite example](src/test/java/io/github/markpollack/workflow/batch/examples/CompositeRecoveryExample.java) and [Spring test](src/test/java/io/github/markpollack/workflow/batch/examples/CompositeWorkflowTest.java) show two calls to a two-Step polling definition. The Spring test recreates the application context after the second fetch commits. [CompositeRecoveryIT](src/test/java/io/github/markpollack/workflow/batch/durable/CompositeRecoveryIT.java) separately proves recovery after actual JVM death, including nested scopes and the local-completion/return gap.

## Static groups and settlement

Use `parallel(label).allSuccessful().branch(name)` with ordinary member sequences, then `end()` and a real continuation or terminal. All branches see the immutable outer facts available when the group opens; sibling-private facts are inaccessible. Declaration order determines member identity and homogeneous result order, regardless of completion order. Heterogeneous products expose distinct typed roles to the normal record binding model. Nested structural products retain their member result references rather than require an application codec for an internal product type.

Group opening atomically saves ordered membership and per-member entry/join coordinates. Step acceptance commits each branch's exact result and continuation; completed branches never rerun after recovery. One thrown branch failure stops its sequence while queued siblings still execute. The join continues only after every member succeeds with its required result. Otherwise the fully settled group fails its enclosing scope with `PARALLEL_FAILED`. Global cancellation/expiry atomically records unresolved revocations and fences late results while preserving already committed member outcomes.

`RunSnapshot.groups()` exposes group identity, phase, opening/settlement time and declaration-ordered member scope/status/cause/result references. An internal structural result reference resolves through its nested group's committed member records; it has no separate application payload. `scopes()` exposes individual graph progress. Inspection is durable evidence, not a live task handle. The [parallel fixture](src/test/java/io/github/markpollack/workflow/batch/examples/ParallelAssessmentExample.java) and [process recovery test](src/test/java/io/github/markpollack/workflow/batch/durable/ParallelRecoveryIT.java) demonstrate typed results and committed-branch reuse.

## Bounded runtime fan-out

`forEach(label).maxItems(n).maxInFlight(k).allSuccessful()` runs a homogeneous authored body per runtime item. The shared compiler selects a compatible typed `List<T>` from legal facts, refusing ambiguity. An item's input can be a record combining its typed item and earlier context. Only the ordered `List<R>` escapes the item region; sibling-private values remain inaccessible.

Group open accepts one complete ordered manifest reference, indexed item snapshots, bounds and all member coordinates in one transaction before any item Step enters. Oversized lists fail `MAX_ITEMS_EXCEEDED` before item effects; there is no truncation. Equal items are separate occurrences. Queued item scopes have no execution frontier; admission changes them to OPEN with one frontier, up to maxInFlight. An OPEN item holds its logical slot through all inner work until its whole body settles. Physical leaf capacity remains independently bounded by maximumConcurrency.

The existing group coordinator admits queued members after both successful and failed item settlement. Every manifest occurrence settles before normal join or ordinary group failure. Successful results use manifest order; empty membership succeeds with a typed empty list. Cancellation/expiry seals queued and admitted unresolved members and fences late outputs while retaining committed outcomes. The caller still drains admitted Java work; no background scheduler or forced interruption is promised.

`RunSnapshot.groups()` exposes the manifest value, bounds and ordered indexed item/result references. Recovery checks manifest-to-item bytes/order, scope coordinates, occupancy, selected graph and result provenance before progressing. It restores saved occupancy and never rediscovers membership or repeats committed items. Unresolved leaf work may repeat with the same logical invocation/input and a new bounded physical attempt.

## Crash recovery, failure and time

A compatible application can reopen after process death and continue unfinished runs. Committed results are reused. A charged attempt without a committed outcome may execute again with its saved input. `ExecutionPolicy.DEFAULT` permits three total physical attempts per logical invocation; exhausting the allowance fails the run. A changed allowance is a compatibility change.

This is not exactly-once external execution. If a process dies after an external effect but before its result commits, recovery can repeat that effect. Use the stable invocation ID for external idempotency where supported. Known step exceptions stop their scope; parallel groups fully settle admitted siblings before propagating failure; there is no engine retry/backoff facility. `resume` returns a terminal run unchanged and does not provide saved-progress restart after terminal failure. That restart policy remains an open product decision.

The default maximum duration is one hour (`DeadlinePolicy.DEFAULT`, profile `local-v1`). An authored shorter duration tightens it; a longer duration is capped. The lower-level validation API accepts another positive finite `DeadlinePolicy`. Admission saves an absolute deadline and its origin, which recovery never extends.

An entered scope saves the minimum of its parent deadline and its own duration from entry. This absolute bound never resets after recovery. `StepContext.deadline()` exposes that effective bound.

Each serialized transition samples the database-side wall clock with a persisted high-water mark. At equality with the deadline, success/progress is ineligible. This sample is the transition's eligibility instant, not its later physical fsync time. Backward clock movement does not rewind the saved clock; forward movement can expire runs early. Expiry is observed during runtime calls rather than by a background timer. The first valid terminal transition wins permanently.

Cancellation and expiry reject later result acceptance; neither stops already running application code. A caller already interrupted on entry is refused. If a step returns normally with the interrupt flag set, its outcome is processed, `resume` stops before a successor and the flag is restored after persistence/guard cleanup. An unchecked failure with an `InterruptedException` cause records step failure, unless another terminal outcome already won, and restores the flag. Arbitrary asynchronous interruption of database I/O is not a cancellation mechanism.

## Ownership and shutdown

[`StoreOwnership`](src/main/java/io/github/markpollack/workflow/batch/durable/StoreOwnership.java) holds a process-level file lock for one canonical database path. A second runtime, in this JVM or another, refuses with `OWNER_ACTIVE`. Ownership has no per-run expiry or renewal and cannot be taken over from a live owner. Process death releases the lock so a replacement process can reopen.

Pass the database base path, without H2's `.mv.db` suffix. Parent directories and an existing database symlink are resolved to their real paths before locking. The sidecar is `<base>.workflow-owner.lock`; the held OS lock, not the mere presence of that file, establishes ownership. Semicolons in supplied or resolved paths refuse to prevent JDBC URL options from changing the database target.

`shutdown(Duration)` stops admission of all new calls, including inspection and cancellation, then drains entered calls. It does not stop an entered `resume` after its current step: that call retains its normal continuation rules. Once callers and workers drain, shutdown closes the executor/store and releases ownership. A timeout returns `false`, leaves the runtime closing and retains ownership/resources; call shutdown again after application work returns. Supplied dependencies remain application-owned.

`close()` uses a thirty-second drainage timeout and throws `SHUTDOWN_INCOMPLETE` if calls remain. Shutdown never forcibly interrupts steps or cancels runs. Attempting shutdown from inside an active call on the same thread refuses rather than waiting on itself.

## Deployment compatibility

[`StepRegistry`](src/main/java/io/github/markpollack/workflow/batch/durable/StepRegistry.java) freezes canonical names and supplied object identities. Duplicate names and aliases for one object refuse; unrelated unused entries are allowed. Preparation records exactly the selected node-to-name associations. Fresh objects may recover when these selections and full contracts match.

[`ExecutionCompatibility`](src/main/java/io/github/markpollack/workflow/batch/durable/ExecutionCompatibility.java) copies declared compatibility facts. Application settings are supplied separately to Step constructors from the same settings source. The manifest records application/build identities, the exact configuration digest, fixed codec identity, Jackson core/databind versions and Java runtime version/vendor/VM name. Admission, execution, admission reuse and terminal resume/result access compare supplied compatibility facts against the saved run. Authored definition, selected registrations/types and execution policy also participate in compatibility checks.

The application must assign a new immutable build ID when code, dependencies, codec implementation or relevant deployment specification changes. Even a plausibly compatible build with a different ID refuses recovery. The runtime checks declared identities and observable contracts; it does not prove that dependency bytes or external services are unchanged. Relevant immutable behavior configuration must be explicit. Reusing a build ID for changed code violates this contract and cannot generally be detected.

Steps use the ordinary application class loader. Workflow does not archive executable code or dependency objects, scan dependency graphs, recreate DI containers or switch loaders for recovery. The application supplies the compatible deployment again.

`open(database[, policy])` without a deployment provides exclusive management-only inspection, discovery and cancellation. It does not enable execution or result decoding without compatible steps.

## Store and verification limits

The store uses H2 embedded file mode with `WRITE_DELAY=0` and format-9 run aggregates. Older (including format 8), incomplete, malformed or unknown store formats refuse before writable initialization; there is no checkpoint migration or automatic conversion. Remote workers and simultaneous embedded owners are unsupported. Values and events are retained indefinitely; no pruning API is provided. Filesystem/hardware durability remains within H2's guarantees; JVM-kill tests are not power-loss tests.

Run `./mvnw -pl workflow-batch -am verify` from the project root for unit, consumer and separate-JVM recovery checks. The process harness records PIDs, kill boundaries, recovered facts and external invocation counts under `workflow-batch/target/durable-evidence/`. The full project gate is `./mvnw verify`.

## IDE reading order and examples

1. [Workflows](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/Workflows.java): staged nonempty sequence, actual objects, explicit terminal, shared validation.
2. [ValidatedWorkflow](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/compiler/ValidatedWorkflow.java): owned definition, semantic graph and value contracts. Follow StructuredWorkflowCompiler into RegionAnalyzer, GraphLowering and GraphVerification for binding and topology checks.
3. [WorkflowGraph](../workflow-flows/src/main/java/io/github/markpollack/workflow/flows/workflow/WorkflowGraph.java): immutable nodes, edges and per-node binding indexes. Supplied objects live separately on ValidatedWorkflow.
4. StepRegistry and ExecutionCompatibility above, then [WorkflowExecutionBindings](src/main/java/io/github/markpollack/workflow/batch/durable/WorkflowExecutionBindings.java): preparation freezes selected names/instances and verifies full types without invoking a Step.
5. DurableWorkflows: start, resolve, prepareAdvance, enterComposite/returnComposite, executeAttempt and commitResult. WorkflowProgress checks graph frontiers; RunIntegrity checks saved relations; ScopedValues preserves exact typed provenance. JdbcRunStore owns short transactions and aggregate persistence; RuntimeLifecycle and StoreOwnership own caller exclusion and local store lifetime.

The compiling [standalone example](src/test/java/io/github/markpollack/workflow/batch/examples/SequentialRecoveryExample.java) demonstrates launch, inspection and process recovery. The [introductory Spring test](src/test/java/io/github/markpollack/workflow/batch/examples/SimpleGreetingWorkflowTest.java) starts with two distinct Step types and unlabeled authoring. The [advanced Spring test](src/test/java/io/github/markpollack/workflow/batch/examples/GreetingWorkflowTest.java) uses ordinary ExampleSettings and constructor injection, derives compatibility from that same source and takes the canonical Step map from the container. A changed selected configuration refuses recovery before entry. A new business input uses a new launch key without changing application settings.

Recovery means continuing unfinished work after process death. A retry of an unresolved invocation consumes another bounded physical attempt with the exact saved input. Operator restart after terminal failure is a separate unresolved policy. Future scheduler/dashboard integrations can use this launch/management boundary; scheduling, launch-request deduplication beyond the current local idempotency key, independent child runs are not provided by this runtime.

Spring qualifiers disambiguate injected beans; DSL labels describe node positions. Parameter injection lets both example configuration classes use `proxyBeanMethods = false` without changing singleton scope. Neither example proxies the Step implementations. Generic Step-proxy refusal in both interface orders and agreeing proxy contracts are tested separately by `StepRegistryTest`; `StepDeclarationTest` covers inherited, raw and unresolved declarations.

`LocalOwnershipTest.anotherRunCommitsWhileOneStepRemainsBlocked` coordinates callers with latches and checks a second run's committed success before releasing the first Step. `distinctRunsOverlapOnCallingThreadsButSameRunCannotDoubleCharge` checks caller-thread identity and duplicate exclusion. `DurableRaceTest.deadlineEqualityRevokesLateCompletionAndDiscoveryMaterializesExpiry` uses a controlled store clock to verify that discovery records expiry. Inspection likewise calls the same observation transition; it is not read-only and can save terminal failure.

### Configured and non-returning definitions

[ConfiguredCompositeExample](src/test/java/io/github/markpollack/workflow/batch/examples/ConfiguredCompositeExample.java)
wraps two definitions both named `poll` in definitions both named `wrapper`. The two fetch
objects receive different constructor settings and stable registry names (`fetch-fast`
and `fetch-careful`). Each call selects the supplied object belonging to its prepared
definition. [ConfiguredCompositeWorkflowTest](src/test/java/io/github/markpollack/workflow/batch/examples/ConfiguredCompositeWorkflowTest.java)
shows the same arrangement as Spring beans, then closes and recreates the context after
inner completion. The saved fast result survives; the second poll produces the careful
service's result. A single injected `Map<String, Step<?, ?>>` supplies the registry.

A definition ending in authored `FAILED` or `CANCELLED` never returns normally. A parent
containing that child must end at the child; adding a normal successor or parent terminal
is rejected as unreachable. The staged fluent API requires a parent terminal, so use the
shared programmatic compiler for this particular shape:

```java
var child = Workflows.define("business-check")
    .then(check).terminate(FAILED, "business rejection").build();
var source = new WorkflowModel.Definition<>("parent", Request.class, Reply.class,
    List.of(new WorkflowModel.Child("check", child)), null);
var parent = ValidatedWorkflow.compile(source, Map.of());
```

Here `check` is an ordinary `Step<Request, Reply>`. The child retains its local FAILED
outcome and the parent completes as FAILED with that cause. An authored child CANCELLED
also propagates as parent failure; external cancellation acts on the whole run.
