# Local durable workflows

`DurableWorkflows` runs compiler-validated sequential workflows using a separate H2 file schema.
It accepts `ValidatedWorkflow` only. Legacy graphs, runners and checkpoint rows are not admission
inputs. Decisions, verdicts, parallel groups, fan-out, loops, children and timers currently refuse
validated execution admission.

The ordinary lifecycle is:

```java
var deployment = new ApplicationDeployment("my-application", "immutable-build-123",
        Map.of("endpoint", "https://example.test"), List.of(MyOperation.class));
// Use deployment.selection(MyOperation.class, Input.class, Output.class)
// for the corresponding placement when compiling validated.
try (var runs = DurableWorkflows.open(Path.of("data/workflows"), deployment)) {
    var admitted = runs.start(validated, "request-123", request);
    var progress = runs.inspect(admitted.runId());
    var completed = runs.resume(admitted.runId(), validated);
    if (completed.status() == RunSnapshot.Status.SUCCEEDED) {
        Object reply = runs.result(admitted.runId(), validated);
    }
}
```

`start` commits admission without invoking a handler. Repeating an idempotency key with the same
input and compatibility facts returns its existing run; conflicting reuse refuses. `discover()`
returns active admissions, including accepted work that has never been dispatched. A replacement
process opens the same file with the compatible deployed application and supplies the same validated
workflow to `resume`. The application supplies its executable code and dependencies after restart.
`cancel(runId, actor, reason)` records a permanent cancellation when it wins the store transition.
`inspect` exposes immutable values/provenance, deliveries, events, deadlines and terminal reasons.
The optional display-name argument to `start` is presentation; authored definition/placement names
remain stable execution identifiers.

## Application deployment

An ordinary implementation directly implements `DurableOperation<Input, Output>` with concrete
declared contracts and a public no-argument constructor. A fresh instance receives the exact
decoded input, a `DeliveryContext` and immutable `Map<String,String>` configuration per delivery.
The context distinguishes logical invocation, physical delivery, generation and absolute deadline.

`ApplicationDeployment` copies the configuration and explicit operation classes. Bind it to a
runtime handle with `open(database, deployment[, policy])`. Its `selection(operationClass,
inputType, outputType)` supplies the compiler's `ExecutableIdentity` for a selected placement.
The fixed registration governs both compatibility checks and actual invocation. Classes use their
ordinary application loader; Workflow does not switch the thread context loader, scan dependencies,
archive JARs or load retained classes. Construction/placement selection remains in the compiler API.

Admission verifies selected class names, public constructors, direct concrete generic signatures,
selected value type/shape/codec contracts and lossless root encoding/decoding. Known mismatches
refuse before delivery. Deeper missing methods or dependencies discovered during invocation record
an explicit handler failure, subject to ownership fencing; exhaustive preflight linkage is unsupported.

Identity and configuration strings must contain well-formed Unicode; malformed surrogate sequences
refuse before hashing. The durable manifest records application ID, immutable build ID, a digest of the exact copied
configuration supplied to handlers, the fixed codec contract, observed Jackson core/databind
versions, and Java runtime version/vendor/VM name. Recovery compares the handle's current supplied
manifest with the saved manifest using exact equality, alongside authored behavior, operation
selection and finite policy identity. A changed build ID refuses even if its code might be compatible.
Terminal resume/result access, admission reuse and lease renewal enforce compatibility too.
`inspect` exposes the manifest independently from authored identity and run identity.

The application must assign a new immutable build ID when code, dependencies, codec implementation
or relevant deployment specification changes, and supply the corresponding complete deployment.
A mutable label or a source commit that can produce different builds is insufficient. Workflow
compares declared identity and the concrete contracts/configuration it can check; it does not prove
that every executable byte or dependency is unchanged. Reusing a build ID for changed code violates
the deployment contract and cannot generally be detected. Reported version metadata is not binary
measurement. Environment, static/DI state, instrumentation, external services and effects remain
application/deployment responsibilities. Relevant immutable behavior configuration must be explicit.

`open(database[, policy])` provides management-only inspection, discovery and cancellation.
Execution requires a deployment registration. There is no force-resume, alternate retained-code
mode, executable reconstruction, codec plugin loader or checkpoint migration.

## Values, ownership and time

Root, effective input and output payloads are immutable snapshots with declared generic type,
shape/codec identity, payload digest and compiler-selected component/consumption provenance.
Application records, supported scalars and concrete lists use the fixed strict Jackson contract;
null application values/items, arbitrary POJOs, raw/wildcard generics and unsupported bounds refuse.
Both encoding and decoding check lossless reconstruction. A missing or incompatible committed
value refuses recovery; historical assembled inputs are never reconstructed from newer values.

The default maximum run duration is one hour (`DeadlinePolicy.DEFAULT`, profile `local-v1`). A null
authored duration selects that default; a shorter authored duration tightens it. A longer duration
is capped. A validated `DeadlinePolicy` may select another positive finite maximum. Admission
persists the absolute deadline and DEFAULT/AUTHORED/POLICY_CAP origin. Recovery and renewal never
extend it. Execution policy defaults to a thirty-second lease and three total charged physical
deliveries per logical invocation. Validated overrides are part of same-run compatibility.

`resume` renews ownership while a handler runs and drains renewal without interrupting database
I/O when it returns. `claim`, `renew` and `advance` additionally support controlled worker loops;
one generation can dispatch a given unresolved invocation only once. Reclaim at lease equality
increments the persisted generation. Result, renewal and continuation require the live owner,
generation, lease and run deadline. Stale output cannot overwrite a replacement's accepted state.

Every transition acquires the database transition lock, then samples a database-side wall clock
with a persisted high-water mark. That sample is its eligibility/linearization instant; it is not
a claim about the later physical fsync completion instant. At equality with the run deadline,
progress/success is ineligible. First valid terminal transition wins permanently. Discovery and
inspection materialize unobserved expiry. Backward clock changes do not move the stored clock
backward; forward clock changes can expire work early. Operate the local host clock accordingly.

State, immutable value references/payloads, continuation, delivery accounting and ordered events
commit together. The deployment manifest joins the admission transaction. No database
transaction spans handler construction or execution. A charged but unresolved delivery may be
redelivered after replacement; its saved effective input is reused. Exhaustion fails explicitly.
This guarantees committed-result reuse and fenced commits, not exactly-once external effects.
Use the stable logical invocation ID for external idempotency where supported. Business exceptions
fail immediately; there is no engine retry/backoff feature.

## Store envelope

The supported store is H2 2.4.240 embedded file mode with `WRITE_DELAY=0`, file locking and short
transactions. Concurrent handles/workers share one JVM; after process death another JVM can reopen
the file. Simultaneous embedded writers in different processes, remote workers and old-checkpoint
migration are unsupported. Cancellation revokes durable authority without interrupting database
threads; physical interruption of external work is not guaranteed.

A global transition lock serializes state changes and inspection across runs. The initial schema
stores one format-2 aggregate per run, with no executable artifact store. Old, absent or unknown
run-format versions refuse before schema initialization; existing rows are not migrated. Values
and events are retained indefinitely; no pruning or migration API is provided. This favors a
bounded, inspectable local implementation over throughput or large-history optimization. Filesystem
and hardware durability remain within H2's guarantees; process-kill tests are not power-loss tests.

Run `./mvnw -pl workflow-batch -am verify` for unit, consumer and separate-JVM recovery tests.
The integration harness records PIDs, force-kill boundaries, recovered database facts and external
invocation counts under `workflow-batch/target/durable-evidence/`.
