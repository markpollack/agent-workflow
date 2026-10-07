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
package io.github.markpollack.workflow.patterns.evaluator;


import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.judgment.Judgment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The optimizer's best-score state, and what it says about trials nobody measured.
 *
 * <p>A jury reports an outcome; it does not always report a measurement. The loop's best-score
 * state has to be able to say "nothing was measured", because the alternative is to report the
 * bottom of the scale — and a run that abstained is not a run that scored badly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EvaluatorOptimizerLoop")
class EvaluatorOptimizerLoopTest {

    @Mock
    private ChatModel chatModel;

    private ChatClient chatClient;
    private List<ToolCallback> tools;

    @BeforeEach
    void setUp() {
        // Built before stubbing begins: the response's own mocks cannot be created inside an
        // in-progress stubbing of chatModel.call().
        ChatResponse actorResponse = response("draft output");
        given(chatModel.getOptions()).willReturn(ChatOptions.builder().build());
        given(chatModel.call(any(Prompt.class))).willReturn(actorResponse);
        chatClient = ChatClient.builder(chatModel).build();
        tools = List.of();
    }

    @Nested
    @DisplayName("Best score of an unmeasured run")
    class UnmeasuredBestScore {

        /**
         * The sharpest statement of the defect: with a plain {@code double} best score, a run in
         * which a jury measured exactly zero and a run in which nothing was measured at all are
         * the same value, so no consumer can tell them apart.
         */
        @Test
        @DisplayName("a run nobody measured must not report the same best score as a run measured at zero")
        void absenceMustNotBeIndistinguishableFromAMeasuredZero() {
            var noJury = run(Optional.empty());
            var measuredZero = run(Optional.of(juryReturning(scored(0.0))));

            assertThat(noJury.bestScore()).isNotEqualTo(measuredZero.bestScore());
        }

        @Test
        @DisplayName("an abstaining jury measures nothing, so it too differs from a measured zero")
        void abstentionMustNotBeIndistinguishableFromAMeasuredZero() {
            var abstaining = run(Optional.of(juryReturning(abstained())));
            var measuredZero = run(Optional.of(juryReturning(scored(0.0))));

            assertThat(abstaining.bestScore()).isNotEqualTo(measuredZero.bestScore());
        }

        @Test
        @DisplayName("no jury: nothing was measured, and there is no best trial either")
        void noJuryLeavesTheBestScoreAbsent() {
            var result = run(Optional.empty());

            assertThat(result.bestScore()).isEmpty();
            assertThat(result.bestTrial()).isEmpty();
            assertThat(result.scoreImprovement()).isEmpty();
        }

        @Test
        @DisplayName("an abstaining jury leaves the best score absent")
        void abstentionLeavesTheBestScoreAbsent() {
            var result = run(Optional.of(juryReturning(abstained())));

            assertThat(result.bestScore()).isEmpty();
            assertThat(result.bestTrial()).isEmpty();
        }

        @Test
        @DisplayName("a jury that measured zero reports zero — a real finding, not an absence")
        void aMeasuredZeroIsReportedAsZero() {
            var result = run(Optional.of(juryReturning(scored(0.0))));

            assertThat(result.bestScore()).hasValue(0.0);
            assertThat(result.bestTrial()).isPresent();
        }
    }

    // -- Helpers --

    private EvaluatorOptimizerResult run(Optional<Jury> jury) {
        EvaluatorOptimizerConfig config = EvaluatorOptimizerConfig.builder()
                .maxTrials(1)
                .timeout(Duration.ofMinutes(1))
                .scoreThreshold(0.8)
                .workingDirectory(Path.of("."))
                .jury(jury.orElse(null))
                .build();
        return EvaluatorOptimizerLoop.builder().config(config).build()
                .execute("improve this", chatClient, tools);
    }

    private static Jury juryReturning(Judgment aggregate) {
        Jury jury = mock(Jury.class);
        when(jury.vote()).thenReturn(
                Verdict.single("test",aggregate));
        return jury;
    }

    /** An aggregate that measured the bottom of the scale — a real finding of zero. */
    private static Judgment scored(double score) {
        return Judgment.scored(score).passingAt(0.5).reasoning("measured " + score).build();
    }

    /** An aggregate that cast no vote, and so measured nothing. */
    private static Judgment abstained() {
        return Judgment.builder().abstain().reasoning("does not apply to this subject").build();
    }

    private static ChatResponse response(String content) {
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(new AssistantMessage(content));

        Usage usage = mock(Usage.class);
        when(usage.getTotalTokens()).thenReturn(Integer.valueOf(100));

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse built = ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(metadata)
                .build();

        ChatResponse spy = Mockito.spy(built);
        when(spy.hasToolCalls()).thenReturn(false);
        return spy;
    }
}
