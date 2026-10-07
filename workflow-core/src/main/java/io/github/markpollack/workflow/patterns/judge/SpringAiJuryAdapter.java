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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.markpollack.workflow.core.LoopState;

import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;
import org.springframework.ai.chat.model.ChatResponse;

import java.nio.file.Path;

/**
 * Supplies a configured native Jury for the current loop observation and invokes it
 * synchronously.
 */
public class SpringAiJuryAdapter {

	private static final Logger log = LoggerFactory.getLogger(SpringAiJuryAdapter.class);

	private final java.util.function.Function<Input, Jury> factory;

	private final Jury configured;

	public record Input(LoopState state, ChatResponse response, Path workingDirectory) {
	}

	private final String juryName;

	public SpringAiJuryAdapter(Jury jury) {
		this(jury, "jury");
	}

	public SpringAiJuryAdapter(Jury jury, String juryName) {
		this.configured = java.util.Objects.requireNonNull(jury);
		this.factory = input -> jury;
		this.juryName = juryName;
	}

	public SpringAiJuryAdapter(java.util.function.Function<Input, Jury> factory, String juryName) {
		this.factory = java.util.Objects.requireNonNull(factory);
		this.configured = null;
		this.juryName = juryName;
	}

	/**
	 * Evaluates the current loop state using the agent-judge jury.
	 * <p>
	 * This is a synchronous call that executes all judges and aggregates their verdicts.
	 * @param state the current loop state
	 * @param response the ChatResponse to evaluate (may be null)
	 * @param workingDirectory the workspace directory for file-based judges
	 * @return the verdict from the jury
	 */
	public Verdict evaluate(LoopState state, ChatResponse response, Path workingDirectory) {
		long startTime = System.currentTimeMillis();

		try {
			Jury jury = java.util.Objects.requireNonNull(factory.apply(new Input(state, response, workingDirectory)));
			Verdict verdict = jury.vote();

			// Log results
			long durationMs = System.currentTimeMillis() - startTime;

			log.debug("{} evaluation completed: runId={}, turn={}, status={}, score={}, duration={}ms", juryName,
					state.runId(), state.currentTurn(), verdict.judgment().status(),
					ScoreText.describe(verdict.judgment().effectiveScore()), durationMs);

			return verdict;

		}
		catch (Exception e) {
			long durationMs = System.currentTimeMillis() - startTime;

			log.error("{} evaluation failed: runId={}, turn={}, error={}, duration={}ms", juryName, state.runId(),
					state.currentTurn(), e.getMessage() != null ? e.getMessage() : "Unknown error", durationMs);

			throw new RuntimeException("Jury evaluation failed", e);
		}
	}

	/**
	 * Returns the fixed configured jury.
	 * @return configured jury
	 * @throws IllegalStateException when this adapter uses a per-observation factory
	 */
	public Jury getJury() {
		if (configured == null)
			throw new IllegalStateException("adapter uses a per-observation jury factory");
		return configured;
	}

}
