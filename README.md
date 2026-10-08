# Agent Workflow

A Java 21 library for typed Steps, reusable workflows, exhaustive decisions and bounded concurrent work, with local JDBC durability and recovery.

[Documentation](https://lab.pollack.ai/projects/agent-workflow) · [0.14.0 release notes](RELEASE_NOTES_0.14.0.md) · [Compiling deterministic example](examples/review-files/README.md) · [Six runnable lessons](https://github.com/markpollack/workflow-dsl-examples/tree/main/executable-dsl)

## Maven

The current executable-DSL release is **0.14.0**. The first milestone was 0.13.0:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>workflow-batch</artifactId>
    <version>0.14.0</version>
</dependency>
```

Native Verdict routing additionally requires `io.github.markpollack:agent-judge-json-jackson2:0.18.0`.

## Build

```bash
./mvnw clean verify
```

Supports typed sequential Steps and derived bindings, nested reusable workflows, exhaustive enum/native Verdict decisions, static parallel composition and bounded runtime fan-out. Durable loops, waits/timers and Workflow committed-event Journal projection are deferred. This milestone does not complete the full R1 roadmap.

Store format 9 refuses older formats; no automatic migration. Recovery requires the exact compatible deployment and reuses committed work. Unresolved external effects can repeat; terminal failed runs cannot be reopened. See the [runtime guide](workflow-batch/README-durable.md) for bounds and lifecycle limitations.

## Fluent completion

`Workflows.Sequence.build()` lets a parent finish with a final child that always fails or cancels.
This additive authoring method is available starting in 0.14.0.
Ordinary returning paths still require explicit terminals; unreachable successors still refuse.
The six-lesson progression also remains compatible with 0.13.0.

## License

[Business Source License 1.1](LICENSE)
