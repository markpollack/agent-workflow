package io.github.markpollack.workflow.batch.durable;

/** Package-private deterministic test coordination; no alternate runtime path. */
interface BoundaryHooks {
    BoundaryHooks NONE=(boundary,run)->{};
    void at(String boundary,String runId);
}
