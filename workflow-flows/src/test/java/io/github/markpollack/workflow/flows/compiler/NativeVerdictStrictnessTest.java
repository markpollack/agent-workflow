package io.github.markpollack.workflow.flows.compiler;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.serialization.VerdictCodec;
import static org.assertj.core.api.Assertions.*;

class NativeVerdictStrictnessTest {

	public record Envelope(String subject, Verdict assessment, String after) {
	}

	@Test
	void duplicateNativeRootPropertyMustRemainRejectedByWorkflowDecoder() {
		var codec = new VerdictCodec();
		String canonical = codec.write(Verdict.observed("native", Judgment.pass("ok"), null));
		String duplicate = canonical.replaceFirst("\\{", "{\"schemaVersion\":6,");
		assertThatThrownBy(() -> codec.read(duplicate)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(
				() -> new TypeContracts().decodeBytes(duplicate.getBytes(StandardCharsets.UTF_8), Verdict.class))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void duplicateNestedNativePropertyMustRemainRejectedByWorkflowDecoder() {
		var codec = new VerdictCodec();
		String canonical = codec.write(Verdict.observed("native", Judgment.pass("ok"), null));
		String duplicate = canonical.replaceFirst("\\{", "{\"schemaVersion\":6,");
		String outer = "{\"subject\":\"s\",\"assessment\":" + duplicate + ",\"after\":\"tail\"}";
		assertThatThrownBy(
				() -> new TypeContracts().decodeBytes(outer.getBytes(StandardCharsets.UTF_8), Envelope.class))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
