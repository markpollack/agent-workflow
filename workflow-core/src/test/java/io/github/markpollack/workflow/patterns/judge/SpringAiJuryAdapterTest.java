package io.github.markpollack.workflow.patterns.judge;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import io.github.markpollack.workflow.core.LoopState;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.verdict.Verdict;
import static org.assertj.core.api.Assertions.*;

class SpringAiJuryAdapterTest {

	@Test
	void factoryConfiguresCurrentNativeJuryFromExactObservation() {
		var retained = Verdict.single("judge", Judgment.pass("observed"));
		var state = LoopState.initial("run");
		var directory = Path.of("work");
		var observed = new AtomicReference<SpringAiJuryAdapter.Input>();
		var adapter = new SpringAiJuryAdapter(input -> {
			observed.set(input);
			return () -> retained;
		}, "configured");
		assertThat(adapter.evaluate(state, null, directory)).isSameAs(retained);
		assertThat(observed.get().state()).isSameAs(state);
		assertThat(observed.get().workingDirectory()).isEqualTo(directory);
		assertThatThrownBy(adapter::getJury).isInstanceOf(IllegalStateException.class);
		Jury fixed = () -> retained;
		assertThat(new SpringAiJuryAdapter(fixed).getJury()).isSameAs(fixed);
	}

}
