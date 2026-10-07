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
package io.github.markpollack.workflow.patterns.judge;

import io.github.markpollack.workflow.core.TerminationReason;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.workflow.strategy.TerminationStrategy;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Termination strategy that uses spring-ai-agents Jury for loop termination decisions.
 * <p>
 * This strategy bridges the spring-ai-agents judge framework with our loop patterns,
 * enabling rich evaluation capabilities (build checks, file existence, LLM judges, etc.)
 * to drive loop termination.
 * <p>
 * The jury is evaluated each iteration, and if the aggregated verdict passes,
 * the loop terminates successfully. A score threshold can optionally be set
 * for partial success scenarios.
 *
 * <p>Example usage:
 * <pre>{@code
 * Jury jury = SimpleJury.builder()
 *     .judge(BuildSuccessJudge.compile(), 0.5)
 *     .judge(BuildSuccessJudge.test(), 0.5)
 *     .votingStrategy(new WeightedAverageStrategy())
 *     .build();
 *
 * TerminationStrategy strategy = JuryTerminationStrategy.builder()
 *     .jury(jury)
 *     .workingDirectory(Path.of("/workspace"))
 *     .observability(observability)
 *     .scoreThreshold(0.8)  // Optional: terminate if score >= 0.8
 *     .build();
 *
 * TurnLimitedLoop<Summary> loop = TurnLimitedLoop.builder()
 *     .terminationStrategy(TerminationStrategy.allOf(List.of(
 *         TerminationStrategy.maxTurns(50),
 *         strategy
 *     )))
 *     .build();
 * }</pre>
 */
public class JuryTerminationStrategy implements TerminationStrategy {

    private final SpringAiJuryAdapter juryAdapter;
    private final Path workingDirectory;
    private final double scoreThreshold;
    private final boolean requirePass;

    private Verdict lastVerdict;

    private JuryTerminationStrategy(Builder builder) {
        this.juryAdapter = new SpringAiJuryAdapter(
                builder.jury,
                builder.juryName
        );
        this.workingDirectory = builder.workingDirectory;
        this.scoreThreshold = builder.scoreThreshold;
        this.requirePass = builder.requirePass;
    }

    @Override
    public TerminationResult check(LoopState state, Verdict providedVerdict) {
        // Use provided verdict if available, otherwise evaluate with jury
        Verdict verdict = providedVerdict;
        if (verdict == null) {
            verdict = juryAdapter.evaluate(state, null, workingDirectory);
        }
        this.lastVerdict = verdict;

        if (verdict == null) {
            return TerminationResult.continueLoop();
        }

        // Check if jury verdict indicates success
        boolean passed = verdict.judgment().pass();
        OptionalDouble score = verdict.judgment().effectiveScore();

        if (requirePass && passed) {
            return TerminationResult.terminate(
                    TerminationReason.SCORE_THRESHOLD_MET,
                    String.format("Jury passed with score %s: %s",
                            ScoreText.describe(score), verdict.judgment().reasoning())
            );
        }

        // An aggregate that made no measurement carries no score to compare, so the loop
        // continues rather than reading "did not evaluate" as a value below the threshold.
        if (!requirePass && score.isPresent() && score.getAsDouble() >= scoreThreshold) {
            return TerminationResult.terminate(
                    TerminationReason.SCORE_THRESHOLD_MET,
                    String.format("Score %.2f >= threshold %.2f: %s",
                            score.getAsDouble(), scoreThreshold, verdict.judgment().reasoning())
            );
        }

        return TerminationResult.continueLoop();
    }

    /**
     * Returns the last verdict from the jury evaluation.
     */
    public Optional<Verdict> getLastVerdict() {
        return Optional.ofNullable(lastVerdict);
    }

    /**
     * Returns the underlying jury adapter.
     */
    public SpringAiJuryAdapter getJuryAdapter() {
        return juryAdapter;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Jury jury;
        private Path workingDirectory;
        private String juryName = "jury";
        private double scoreThreshold = 1.0;
        private boolean requirePass = true;

        public Builder jury(Jury jury) {
            this.jury = jury;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Builder juryName(String juryName) {
            this.juryName = juryName;
            return this;
        }

        /**
         * Sets the score threshold for termination (when requirePass is false).
         */
        public Builder scoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
            return this;
        }

        /**
         * If true (default), requires jury to pass for termination.
         * If false, terminates when score >= scoreThreshold.
         */
        public Builder requirePass(boolean requirePass) {
            this.requirePass = requirePass;
            return this;
        }

        public JuryTerminationStrategy build() {
            if (jury == null) {
                throw new IllegalStateException("Jury must be set");
            }
            if (workingDirectory == null) {
                throw new IllegalStateException("Working directory must be set");
            }
            return new JuryTerminationStrategy(this);
        }
    }
}
