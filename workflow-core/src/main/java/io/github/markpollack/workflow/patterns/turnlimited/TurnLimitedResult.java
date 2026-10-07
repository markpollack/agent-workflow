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
package io.github.markpollack.workflow.patterns.turnlimited;

import io.github.markpollack.workflow.core.LoopResult;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.workflow.core.LoopStatus;
import io.github.markpollack.workflow.core.TerminationReason;
import io.github.markpollack.judge.verdict.Verdict;
import org.springframework.lang.Nullable;

import java.time.Duration;
import java.util.OptionalDouble;

/**
 * Result of executing a TurnLimitedLoop.
 * <p>
 * Provides access to common result data via the LoopResult interface,
 * plus pattern-specific data such as the final loop state and last jury verdict.
 */
public record TurnLimitedResult(
        // Common fields (from LoopResult)
        String runId,
        String output,
        LoopStatus status,
        TerminationReason reason,
        int turnsCompleted,
        Duration totalDuration,
        long totalTokens,
        double estimatedCost,

        // Pattern-specific fields
        LoopState finalState,
        @Nullable Verdict lastVerdict
) implements LoopResult {

    /**
     * Returns the final score from the last jury evaluation.
     * <p>
     * Empty when no jury ran, and equally empty when the jury ran but reached no finding to
     * measure. Both are the absence of a score rather than a score of zero, and a caller that
     * needs to tell them apart reads {@link #lastVerdict()}.
     */
    public OptionalDouble finalScore() {
        if (lastVerdict == null) return OptionalDouble.empty();
        return lastVerdict.judgment().effectiveScore();
    }

    /**
     * Returns true if the jury passed on the final evaluation.
     */
    public boolean juryPassed() {
        return lastVerdict != null && lastVerdict.judgment().pass();
    }

    /**
     * Returns true if the loop terminated due to stuck detection.
     */
    public boolean wasStuck() {
        return reason() == TerminationReason.STUCK_DETECTED;
    }

    /**
     * Returns true if the loop terminated due to max turns reached.
     */
    public boolean maxTurnsReached() {
        return reason() == TerminationReason.MAX_TURNS_REACHED;
    }

    /**
     * Returns true if the loop terminated due to timeout.
     */
    public boolean timedOut() {
        return reason() == TerminationReason.TIMEOUT;
    }

    /**
     * Returns true if the finish tool was called.
     */
    public boolean finishToolCalled() {
        return reason() == TerminationReason.FINISH_TOOL_CALLED;
    }

    /**
     * Creates a successful result.
     */
    public static TurnLimitedResult success(
            String runId,
            String output,
            LoopState state,
            @Nullable Verdict lastVerdict
    ) {
        return new TurnLimitedResult(
                runId,
                output,
                LoopStatus.COMPLETED,
                TerminationReason.FINISH_TOOL_CALLED,
                state.currentTurn(),
                state.elapsed(),
                state.totalTokensUsed(),
                state.estimatedCost(),
                state,
                lastVerdict
        );
    }

    /**
     * Creates a result with a specific termination reason.
     */
    public static TurnLimitedResult terminated(
            String runId,
            String output,
            TerminationReason reason,
            LoopState state,
            @Nullable Verdict lastVerdict
    ) {
        return new TurnLimitedResult(
                runId,
                output,
                LoopStatus.COMPLETED,
                reason,
                state.currentTurn(),
                state.elapsed(),
                state.totalTokensUsed(),
                state.estimatedCost(),
                state,
                lastVerdict
        );
    }

    /**
     * Creates a failed result.
     */
    public static TurnLimitedResult failed(String runId, LoopState state) {
        return new TurnLimitedResult(
                runId,
                null,
                LoopStatus.FAILED,
                TerminationReason.ERROR,
                state.currentTurn(),
                state.elapsed(),
                state.totalTokensUsed(),
                state.estimatedCost(),
                state,
                null
        );
    }
}
