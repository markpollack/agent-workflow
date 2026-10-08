# Review files with runtime fan-out

This standalone Java 21 consumer uses `io.github.markpollack:workflow-batch:0.13.0`, with no project parent or BOM. Until publication it needs separately staged stable artifacts. All Steps are deterministic fixtures; no real provider, credentials or network calls.

From the repository root after installing the release artifacts, or after their Central publication:

```bash
./mvnw -f examples/review-files/pom.xml compile exec:java \
  -Dexec.mainClass=example.FanOutReviewExample -Dexec.args=/tmp/review-files-store
```

Run again on that same directory with the same JDK/deployment to reuse committed work. Output:

```text
Report[revision=revision-17, reviews=[FileReview[path=A.java, acceptable=true], FileReview[path=B.java, acceptable=false]]]
```

[Full compiling source](src/main/java/example/FanOutReviewExample.java): discovery returns `List<FilePatch>`, review receives each exact `FilePatch`, and report receives `ReportInput(ReviewRequest, List<FileReview>)` derived from the original request and ordered aggregate.

```java
Workflows.define("review-changed-files")
    .then(discoverFiles)
    .forEach("files").maxItems(40).maxInFlight(4).allSuccessful()
        .then(reviewFile)
    .end()
    .then(writeReport)
    .terminate(SUCCEEDED)
    .build();
```

Equal inputs still have distinct indexed item identities. The manifest is saved before item execution and reused during recovery. `maxItems` refuses oversized collections before item effects. `maxInFlight` bounds admitted unsettled item workflows; physical Step concurrency has its own runtime bound. An item retains logical occupancy while descendants execute. Empty input yields an empty list. Results remain in input order, regardless of completion order. A negative business review is a value; execution failures prevent a successful aggregate.

IDE path: this example → `Workflows` → `ValidatedWorkflow`/`WorkflowGraph` → `DurableWorkflows`. The [runtime guide](../../workflow-batch/README-durable.md) explains saved inputs, settlement, deadlines and recovery limits.
