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
package io.github.markpollack.workflow.strategy;

import io.github.markpollack.workflow.core.TerminationReason;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.judge.verdict.Verdict;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Strategy for determining when an agent loop should terminate.
 * <p>
 * Different patterns use different termination conditions:
 * <ul>
 *   <li>Turn-Limited: max turns + finish tool + score threshold</li>
 *   <li>Evaluator-Optimizer: max trials + success evaluation</li>
 *   <li>State Machine: terminal state reached</li>
 *   <li>Finish Tool: special tool invoked</li>
 *   <li>Pre-Planned: all steps completed</li>
 * </ul>
 */
@FunctionalInterface
public interface TerminationStrategy {

    /**
     * Checks if the loop should terminate given current state and verdict.
     *
     * @param state current loop state
     * @param verdict latest verdict from jury (may be null)
     * @return termination result
     */
    TerminationResult check(LoopState state, Verdict verdict);

    /**
     * Result of termination check.
     */
    record TerminationResult(
            boolean shouldTerminate,
            TerminationReason reason,
            String message
    ) {
        public static TerminationResult continueLoop() {
            return new TerminationResult(false, TerminationReason.NOT_TERMINATED, null);
        }

        public static TerminationResult terminate(TerminationReason reason, String message) {
            return new TerminationResult(true, reason, message);
        }

        public static TerminationResult terminate(TerminationReason reason) {
            return new TerminationResult(true, reason, reason.name());
        }
    }

    /**
     * Combines multiple strategies - first one to terminate wins.
     */
    static TerminationStrategy allOf(List<TerminationStrategy> strategies) {
        return (state, verdict) -> {
            for (var strategy : strategies) {
                var result = strategy.check(state, verdict);
                if (result.shouldTerminate()) {
                    return result;
                }
            }
            return TerminationResult.continueLoop();
        };
    }

    /**
     * Creates a strategy that terminates when max turns reached.
     */
    static TerminationStrategy maxTurns(int maxTurns) {
        return (state, verdict) -> state.maxTurnsReached(maxTurns)
                ? TerminationResult.terminate(TerminationReason.MAX_TURNS_REACHED, "Reached max turns: " + maxTurns)
                : TerminationResult.continueLoop();
    }

    /**
     * Creates a strategy that terminates when timeout exceeded.
     */
    static TerminationStrategy timeout(java.time.Duration timeout) {
        return (state, verdict) -> state.timeoutExceeded(timeout)
                ? TerminationResult.terminate(TerminationReason.TIMEOUT, "Timeout exceeded: " + timeout)
                : TerminationResult.continueLoop();
    }

    /**
     * Creates a strategy that terminates when cost limit exceeded.
     */
    static TerminationStrategy costLimit(double limit) {
        return (state, verdict) -> state.costExceeded(limit)
                ? TerminationResult.terminate(TerminationReason.COST_LIMIT_EXCEEDED,
                        String.format("Cost $%.4f > limit $%.4f", state.estimatedCost(), limit))
                : TerminationResult.continueLoop();
    }

    /**
     * Creates a strategy that terminates when stuck (same output repeated).
     */
    static TerminationStrategy stuckDetection(int threshold) {
        return (state, verdict) -> state.isStuck(threshold)
                ? TerminationResult.terminate(TerminationReason.STUCK_DETECTED,
                        "Agent stuck: same output " + threshold + " times")
                : TerminationResult.continueLoop();
    }

    /**
     * Creates a strategy that terminates on abort signal.
     */
    static TerminationStrategy abortSignal() {
        return (state, verdict) -> state.abortSignalled()
                ? TerminationResult.terminate(TerminationReason.EXTERNAL_SIGNAL, "Abort signal received")
                : TerminationResult.continueLoop();
    }

    /**
     * Creates a strategy that terminates when jury passes with threshold.
     */
    static TerminationStrategy juryScore(double threshold) {
        return (state, verdict) -> {
            if (verdict == null) {
                return TerminationResult.continueLoop();
            }
            if (verdict.judgment().pass()) {
                return TerminationResult.terminate(TerminationReason.SCORE_THRESHOLD_MET,
                        "Jury passed");
            }
            // Check score threshold. A verdict that reached no finding — an abstention or an
            // evaluation error — carries no score to compare, and the loop continues rather
            // than reading that absence as a score below the threshold.
            OptionalDouble score = verdict.judgment().effectiveScore();
            if (score.isPresent() && score.getAsDouble() >= threshold) {
                return TerminationResult.terminate(TerminationReason.SCORE_THRESHOLD_MET,
                        String.format("Score %.2f >= threshold %.2f", score.getAsDouble(), threshold));
            }
            return TerminationResult.continueLoop();
        };
    }
}
