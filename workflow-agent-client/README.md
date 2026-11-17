# Workflow Agent Client

`workflow-agent-client` lets a Workflow step run an agent through Agent Client.
- `WorkflowAgentClient` implements the step interface `io.github.markpollack.workflow.flows.steps.AgentClient`.
- Pass it to `AgentClientStep`. Each step invocation then becomes one synchronous Agent Client `run()`.

```xml
<dependency>
  <groupId>io.github.markpollack</groupId>
  <artifactId>workflow-agent-client</artifactId>
  <version>${agent-workflow.version}</version>
</dependency>
```

The module depends on `workflow-flows`, which holds the step interface, and on `agent-client-core`. Add an Agent
Client provider module, such as `agent-client-claude` or `agent-client-junie`. Add `workflow-batch` too if the workflow
runs durably.

```java
try (AgentClient client = AgentClient.builder(provider)
        .defaultWorkingDirectory(project)
        .executionRecorder(new JournalExecutionRecorder(Path.of("archive"), "my-app"))   // optional
        .build()) {
    var step = AgentClientStep.of(WorkflowAgentClient.of(client), "Fix the failing test in {input}");
    var workflow = Workflows.define("fix").then(step).terminate(SUCCEEDED).build();
    // run it with the Workflow runtime, for example DurableWorkflows
}
```

## What a step does

- **The prompt is the goal.** Workflow resolves the step's template, and the resolved text becomes the run's goal.
- **Each run is correlated with its step.** It carries the step's Workflow run, invocation and attempt ids as its
  `ApplicationCorrelation`. With a recorder such as Agent Journal's `JournalExecutionRecorder`, the archive then
  records which Workflow step and attempt each agent execution belongs to.
- **An unsuccessful run fails the step.** The adapter throws `WorkflowAgentClientException`, which carries the
  response, so Workflow's retry and failure handling apply. A successful run returns the result text. The trace path in
  `executeForResult` is always `null`; use a recorder for the execution record.
- **The step's deadline is Workflow's.** To bound the agent run itself, set an execution timeout with the request
  customizer.

`WorkflowAgentClient.builder(client)` takes two optional settings:
- `request(spec -> ...)` adjusts every request after the goal and correlation are set, for example
  `spec.executionTimeout(Duration.ofMinutes(10))` or a working directory;
- `onResponse((context, response) -> ...)` observes every response, successful or not, before the step completes or
  fails.

The caller keeps and closes the `AgentClient`.

## Dependency direction

Workflow is above Agent Client: this module depends on Client, and Client never depends on Workflow. No other Workflow
module may depend on Agent Client. The build's `ban-agent-client` enforcer rule lists every artifact Agent Client
publishes and applies to every module except this one.

## Live proof

`JunieWorkflowRecordingIT` runs a durable Workflow whose `AgentClientStep` goes through this adapter to Junie. Agent
Journal's recorder archives the run, Journal's `FinalAssembly` assembles it, and a separate JVM reads it back with
Journal's `AssemblyOracle`. That JVM is `workflow-agent-client-reader`, which is not published, and its classpath holds
only Journal's capture TCK. The verdict must be ACCEPTED.

The test calls a real model, so it runs only when you choose one: it is skipped unless `JUNIE_IT_MODEL` is set and a
Junie CLI is available. A local OpenAI-compatible server costs nothing. Put a custom model profile `<name>.json` in a
folder:

```json
{
  "baseUrl": "http://localhost:1234/v1/chat/completions",
  "apiType": "OpenAICompletion",
  "id": "qwen/qwen3-coder-30b",
  "displayName": "Local qwen3-coder-30b",
  "apiKey": "local"
}
```

```bash
JUNIE_IT_MODEL_LOCATION=<folder> JUNIE_IT_MODEL=custom:<name> JUNIE_IT_EVIDENCE=$(mktemp -d) \
  ./mvnw verify -pl workflow-agent-client -am -Dit.test=JunieWorkflowRecordingIT -Dtest=NONE \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

The server must accept Junie's full prompt, which exceeds 12,000 tokens even for a simple task. The evidence directory
keeps the archive, the reader's report (`reader-report.json`) and an index of the run, session and verdict ids
(`evidence-index.json`). On `main`, the module builds against the other AgentWorks projects' `main` snapshots;
install them locally first.
