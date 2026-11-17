# Agent Workflow 1.1.0 — draft

Draft notes for the next release; main is 1.1.0-SNAPSHOT. Requires Java 21.

## Security

- **CVE-2026-75140** (HIGH): denial of service in `org.jsoup:jsoup` from deeply nested documents, fixed in jsoup 1.23.2. Also **CVE-2026-71497** (MEDIUM), fixed in jsoup 1.23.1. `workflow-agents` reached jsoup 1.15.4 through `spring-ai-agent-utils` 0.10.0 and `flexmark-html2md-converter` 0.64.8, which `SmartWebFetchTool` uses to convert fetched pages to Markdown. `workflow-agents` now declares jsoup **1.24.1** directly, so applications resolve the patched version, and the published POM's floor check enforces at least 1.23.2.

HTML-to-Markdown conversion is unchanged: a new offline test converts a representative page and checks the result is byte-identical to the output on jsoup 1.15.4.

## New module

- **`workflow-agent-client`**: Agent Client as a Workflow agent. `WorkflowAgentClient` implements the `AgentClient` step interface, so each `AgentClientStep` invocation is one synchronous Agent Client `run()`, correlated with the step's Workflow run, invocation and attempt ids. An unsuccessful run fails the step with `WorkflowAgentClientException`. It depends on `workflow-flows` and `agent-client-core`. It is the only Workflow module that depends on Agent Client; the build bans Agent Client from every other module.
- A live proof, `JunieWorkflowRecordingIT`, runs a durable Workflow through this adapter to Junie, and Agent Journal must verify the recording as ACCEPTED. It runs only when a model is chosen (`JUNIE_IT_MODEL`).
