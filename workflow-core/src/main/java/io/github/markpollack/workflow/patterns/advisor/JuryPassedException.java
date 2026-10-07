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
package io.github.markpollack.workflow.patterns.advisor;

import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.workflow.core.TerminationReason;
import io.github.markpollack.judge.verdict.Verdict;
import org.springframework.ai.chat.client.ChatClientResponse;

import java.util.OptionalDouble;

/**
 * Exception thrown when jury evaluation passes (successful completion).
 * <p>
 * This is a "happy path" termination - the agent has successfully completed
 * the task as verified by the jury. It carries the verdict details for
 * reporting and analysis.
 */
public class JuryPassedException extends AgentLoopTerminatedException {

    private final Verdict verdict;

    /**
     * Creates a new jury passed exception.
     *
     * @param verdict the jury verdict
     * @param state the loop state at completion
     * @param response the final response
     */
    public JuryPassedException(Verdict verdict, LoopState state, ChatClientResponse response) {
        super(
                TerminationReason.SCORE_THRESHOLD_MET,
                formatMessage(verdict),
                state,
                response
        );
        this.verdict = verdict;
    }

    /**
     * Returns the jury verdict.
     */
    public Verdict getVerdict() {
        return verdict;
    }

    /**
     * Returns the aggregated score from the verdict as a normalized value (0.0 to 1.0), or
     * empty when the jury passed without measuring anything.
     * <p>
     * Absence here is not a score of zero: a judge that reports an outcome and no measurement
     * is ordinary, and this exception is only ever raised on a passing verdict.
     */
    public OptionalDouble getScore() {
        return verdict == null ? OptionalDouble.empty() : verdict.judgment().effectiveScore();
    }

    private static String formatMessage(Verdict verdict) {
        if (verdict == null) {
            return "Jury passed";
        }
        OptionalDouble score = verdict.judgment().effectiveScore();
        return score.isPresent()
                ? String.format("Jury passed with score %.2f", score.getAsDouble())
                : "Jury passed";
    }
}
