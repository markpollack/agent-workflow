package io.github.markpollack.workflow.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Legacy context carrier for agent strategies and tracing. Durable Steps use typed
 * inputs/results and metadata-only StepContext.
 * <p>
 * Carries framework well-known keys (run identity, iteration count, accumulated cost)
 * plus arbitrary user-defined keys — all type-safe via {@link ContextKey}.
 * <p>
 * Immutable: mutations produce a new instance via {@link #mutate()}.
 * Legacy callers can merge derived contexts explicitly; this object is not durable
 * Workflows binding or saved item state.
 * <p>
 * Create with {@link #create()} for a new run, or {@link #withRunId(String)} when
 * a specific identifier is needed (e.g., for traceability with an external job ID).
 */
public final class AgentContext {

    // -------------------------------------------------------------------------
    // Framework well-known keys
    // -------------------------------------------------------------------------

    /** Unique identifier for this workflow execution. */
    public static final ContextKey<String> WORKFLOW_RUN_ID =
            ContextKey.of("workflowRunId", String.class);

    /** Name of the workflow being executed. */
    public static final ContextKey<String> WORKFLOW_NAME =
            ContextKey.of("workflowName", String.class);

    /** Name of the currently executing step. */
    public static final ContextKey<String> CURRENT_STEP =
            ContextKey.of("currentStep", String.class);

    /** Number of loop iterations completed (for repeatUntil loops). */
    public static final ContextKey<Integer> ITERATION_COUNT =
            ContextKey.of("iterationCount", Integer.class);

    /** Total USD cost accumulated across all steps in this run. */
    public static final ContextKey<Double> ACCUMULATED_COST =
            ContextKey.of("accumulatedCost", Double.class);

    /** Total tokens consumed across all steps in this run. */
    public static final ContextKey<Long> ACCUMULATED_TOKENS =
            ContextKey.of("accumulatedTokens", Long.class);

    /** Judge verdict from a failed JudgeGate evaluation. The retry step reads this for feedback. */
    public static final ContextKey<Object> JUDGE_VERDICT =
            ContextKey.of("judgeVerdict", Object.class);

    /**
     * Accumulating trail of every gate verdict in the run, in evaluation order — recorded on every
     * decision (PASS and FAIL), unlike the singular {@link #JUDGE_VERDICT}. Downstream steps (reports,
     * audits) read this to render the full judge cascade without a bespoke per-consumer gate wrapper.
     * Elements are {@code agent-judge} {@code Verdict} objects, held as {@code Object} so this module
     * stays judge-free.
     */
    @SuppressWarnings("unchecked")
    public static final ContextKey<List<Object>> JUDGE_VERDICTS =
            (ContextKey<List<Object>>) (ContextKey<?>) ContextKey.of("judgeVerdicts", List.class);

    /** Reflector-generated feedback text from a failed JudgeGate evaluation. */
    public static final ContextKey<String> JUDGE_REFLECTION =
            ContextKey.of("judgeReflection", String.class);

    /** Trace file path from the most recent agent step execution. */
    public static final ContextKey<String> TRACE_PATH =
            ContextKey.of("tracePath", String.class);

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    private final Map<ContextKey<?>, Object> entries;

    private AgentContext(Map<ContextKey<?>, Object> entries) {
        this.entries = Map.copyOf(entries);
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a new context with a random run ID and no other entries.
     *
     * @return a new AgentContext
     */
    public static AgentContext create() {
        return new AgentContext(Map.of(WORKFLOW_RUN_ID, UUID.randomUUID().toString()));
    }

    /**
     * Creates a new context with the given run ID and no other entries.
     *
     * @param runId the run identifier
     * @return a new AgentContext
     */
    public static AgentContext withRunId(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return new AgentContext(Map.of(WORKFLOW_RUN_ID, runId));
    }

    // -------------------------------------------------------------------------
    // Type-safe access
    // -------------------------------------------------------------------------

    /**
     * Returns the value associated with the given key, if present.
     *
     * @param key the context key
     * @param <T> the value type
     * @return an Optional containing the value, or empty if absent
     */
    public <T> Optional<T> get(ContextKey<T> key) {
        Object value = entries.get(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(key.type().cast(value));
    }

    /**
     * Returns the value associated with the given key, or throws if absent.
     *
     * @param key the context key
     * @param <T> the value type
     * @return the value
     * @throws NoSuchElementException if the key is not present
     */
    public <T> T require(ContextKey<T> key) {
        return get(key).orElseThrow(() ->
                new NoSuchElementException("Required context key not present: " + key));
    }

    // -------------------------------------------------------------------------
    // Convenience accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the run identifier for this execution.
     *
     * @return the run ID
     */
    public String runId() {
        return require(WORKFLOW_RUN_ID);
    }

    // -------------------------------------------------------------------------
    // Immutable mutation
    // -------------------------------------------------------------------------

    /**
     * Returns a builder pre-populated with all entries from this context.
     * Modifications on the builder produce a new {@code AgentContext};
     * this instance is unchanged.
     *
     * @return a new Builder
     */
    public Builder mutate() {
        return new Builder(new HashMap<>(entries));
    }

    /**
     * Returns a new context with all entries from {@code donor} overlaid on this context.
     * Entries present in {@code donor} take precedence over entries with the same key.
     *
     * @param donor the context whose entries to overlay onto this one
     * @return a new merged context
     */
    public AgentContext mergeFrom(AgentContext donor) {
        Map<ContextKey<?>, Object> merged = new HashMap<>(this.entries);
        merged.putAll(donor.entries);
        return new AgentContext(merged);
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Builder for constructing {@link AgentContext} instances.
     * <p>
     * Follows Spring AI's {@code ChatClientRequest.mutate()} pattern:
     * immutable copy-with via builder.
     */
    public static final class Builder {

        private final Map<ContextKey<?>, Object> entries;

        private Builder(Map<ContextKey<?>, Object> entries) {
            this.entries = entries;
        }

        /**
         * Sets a typed key-value pair in the context being built.
         *
         * @param key   the context key
         * @param value the value
         * @param <T>   the value type
         * @return this builder
         */
        public <T> Builder with(ContextKey<T> key, T value) {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(value, "value must not be null");
            entries.put(key, value);
            return this;
        }

        /**
         * Removes a key from the context being built.
         *
         * @param key the context key to remove
         * @return this builder
         */
        public Builder without(ContextKey<?> key) {
            entries.remove(key);
            return this;
        }

        /**
         * Builds and returns a new immutable {@link AgentContext}.
         *
         * @return the built context
         */
        public AgentContext build() {
            return new AgentContext(entries);
        }
    }

    @Override
    public String toString() {
        return "AgentContext[runId=" + get(WORKFLOW_RUN_ID).orElse("(none)") + ", keys=" + entries.keySet() + "]";
    }
}
