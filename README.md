# Agent Workflow

A Java 21 library for typed Steps, reusable workflows, exhaustive decisions and bounded concurrent work, with local JDBC durability and recovery.

[Documentation](https://lab.pollack.ai/projects/agent-workflow) · [1.0.0 release notes](RELEASE_NOTES_1.0.0.md) · [Compiling deterministic example](examples/review-files/README.md) · [Six runnable lessons](https://github.com/markpollack/workflow-dsl-examples/tree/main/executable-dsl)

## Maven

The current release is **1.0.0**:

```xml
<dependency>
    <groupId>io.github.markpollack</groupId>
    <artifactId>workflow-batch</artifactId>
    <version>1.0.0</version>
</dependency>
```

Native Verdict routing additionally requires `io.github.markpollack:agent-judge-json-jackson2:1.0.0`.

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

## Spring Boot, Quarkus and Micronaut

Each framework gets one integration module; none of them depends on Spring AI or Agent Client.

| Framework | Module |
|---|---|
| Spring Boot 4.1 | `workflow-spring-boot-autoconfigure` |
| Quarkus 3.40 | `workflow-quarkus` (extension; `workflow-quarkus-deployment` holds its build steps) |
| Micronaut 4.10 | `workflow-micronaut` |

They provide the same beans from the same `agent-workflow.*` properties:

| Bean | From |
|---|---|
| `ExecutionPolicy` | `agent-workflow.execution.maximum-attempts`, `maximum-depth`, `maximum-invocations`, `maximum-concurrency`; unset bounds keep the defaults |
| `StepRegistry` | every `Step` bean, under its bean name (Quarkus and Micronaut: its `@Named` name, else its class name) |
| `ExecutionCompatibility` | `agent-workflow.application-id`, `agent-workflow.build-id`, `agent-workflow.configuration.*` |
| `DurableWorkflows` | `agent-workflow.database`, the H2 base file path; closed with the application |

```properties
agent-workflow.database=/var/lib/reviews/runs
agent-workflow.application-id=reviews
agent-workflow.build-id=2026.11.15
```

An application bean of the same type replaces each one. A prepackaged workflow can therefore ship as a JAR of `Step` beans and a `ValidatedWorkflow` bean, and an application swaps just the step it needs: in Spring Boot the JAR declares that step `@ConditionalOnMissingBean`, in Quarkus `@DefaultBean`, and in Micronaut the application's step declares `@Replaces`.

## License

[Business Source License 1.1](LICENSE)
