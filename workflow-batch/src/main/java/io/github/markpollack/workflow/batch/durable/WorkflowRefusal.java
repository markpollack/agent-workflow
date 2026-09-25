package io.github.markpollack.workflow.batch.durable;

/** An explicit lifecycle refusal; existing durable facts are preserved. */
public final class WorkflowRefusal extends IllegalStateException {
    private final String code;
    public WorkflowRefusal(String code, String message) { super(message); this.code=code; }
    public WorkflowRefusal(String code, String message, Throwable cause) { super(message,cause); this.code=code; }
    public String code() { return code; }
}
