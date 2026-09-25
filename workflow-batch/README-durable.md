# Local durable workflows

`DurableWorkflows` runs compiler-validated sequential workflows using a separate H2 file schema.
It accepts `ValidatedWorkflow` only. Legacy graphs, runners and checkpoint rows are not admission
inputs. Decisions, verdicts, parallel groups, fan-out, loops, children and timers currently refuse
validated execution admission.

The ordinary lifecycle is:

```java
try (var runs = DurableWorkflows.open(Path.of("data/workflows"))) {
    var admitted = runs.start(validated, "request-123", request, executableBundle);
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
process opens the same file and supplies the same validated workflow to `resume`. It does not need
the original application artifact files: retained selections are read from the database.
`cancel(runId, actor, reason)` records a permanent cancellation when it wins the store transition.
`inspect` exposes immutable values/provenance, deliveries, events, deadlines and terminal reasons.
The optional display-name argument to `start` is presentation; authored definition/placement names
remain stable execution identifiers.

## Executable packaging

An ordinary implementation directly implements `DurableOperation<Input, Output>` with concrete
declared contracts and a public no-argument constructor. A fresh instance receives the exact
decoded input, a `DeliveryContext` and immutable `Map<String,String>` configuration per delivery.
The context distinguishes logical invocation, physical delivery, generation and absolute deadline.

Capture explicit application class directories or JARs and their dependencies with
`ExecutableBundle.capture(classpath, configuration)`. Its `selection(entryPoint, inputType,
outputType)` supplies the compiler's `ExecutableIdentity` for each selected placement. One bundle
and configuration cover a run. Construction/placement selection remains in the compiler API;
the lifecycle needs no database entities or checkpoint manipulation.

The image retains application class/resource bytes, original JAR bytes, the fixed compiler codec
location and Jackson annotation/core/databind artifacts, shared leaf API bytes and a Java runtime
identity. Its closure, codec and configuration digests are distinct. Multi-release JAR entries are
selected for the current Java runtime. Different duplicate resources, native libraries and
manifest `Class-Path` expansion refuse. Class directories are snapshots, not watched deployments.

Admission verifies symbolic class/member linkage of selected operations and declared application
types, their concrete Java signatures, retained type/shape/codec contracts, and root decoding.
Dispatch and recovery verify retained bytes and selections again. Application/codec classes load
from an in-memory copy of the verified image, with no application-classpath fallback. Only Java
platform classes and the two verified shared leaf API types delegate to the host loader. Missing
or changed retained selections refuse; there is no force-resume or codec plugin override.

This is explicit local packaging, not automatic discovery of every possible runtime dependency.
Reflective class names, service/resource lookups and dynamically selected dependencies must be
included by the deployment. The host JVM/platform and instrumentation remain trusted deployment
infrastructure; runtime vendor/version and shared API resource identity are checked, not a full
JVM binary measurement. Hashing does not freeze external services, system properties, environment,
files, native behavior or undeclared DI state. Operations must make relevant immutable behavior
configuration explicit. External responses and effects remain external facts.

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
commit together. Executable/configuration retention joins the admission transaction. No database
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
stores one versioned aggregate per run and content-addressed retained artifacts. Values, artifacts
and events are retained indefinitely; no pruning or migration API is provided. This favors a
bounded, inspectable local implementation over throughput or large-history optimization. Filesystem
and hardware durability remain within H2's guarantees; process-kill tests are not power-loss tests.

Run `./mvnw -pl workflow-batch -am verify` for unit, consumer and separate-JVM recovery tests.
The integration harness records PIDs, force-kill boundaries, recovered database facts and external
invocation counts under `workflow-batch/target/durable-evidence/`.
