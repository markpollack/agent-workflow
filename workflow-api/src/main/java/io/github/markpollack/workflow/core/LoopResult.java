/*
 * Copyright 2024-2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://mariadb.com/bsl11/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.markpollack.workflow.core;

import java.time.Duration;

/**
 * Common contract for all loop results.
 * <p>
 * Implementations provide pattern-specific data as additional fields.
 * This is a regular interface (not sealed) to allow user-defined
 * loop implementations with custom result types.
 *
 * This legacy agent-loop contract is separate from durable Workflows composition.
 */
public interface LoopResult {

    /**
     * Unique identifier for this loop execution.
     */
    String runId();

    /**
     * The final output text from the loop.
     */
    String output();

    /**
     * Status of the loop execution.
     */
    LoopStatus status();

    /**
     * Reason the loop terminated.
     */
    TerminationReason reason();

    /**
     * Number of turns completed before termination.
     */
    int turnsCompleted();

    /**
     * Total wall-clock duration of the loop execution.
     */
    Duration totalDuration();

    /**
     * Total tokens consumed across all LLM calls.
     */
    long totalTokens();

    /**
     * Estimated cost in USD.
     */
    double estimatedCost();

    /**
     * Returns true if the loop completed successfully.
     */
    default boolean isSuccess() {
        return status() == LoopStatus.COMPLETED;
    }

    /**
     * Returns true if the loop failed or errored.
     */
    default boolean isFailure() {
        return status() == LoopStatus.FAILED || status() == LoopStatus.ERROR;
    }
}
