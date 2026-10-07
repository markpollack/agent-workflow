package io.github.markpollack.workflow.batch.examples;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class PrReviewDecisionExampleTest {

	@TempDir
	Path directory;

	@Test
	void prReviewRetainsSubjectEvidenceAndRecoversWithoutRepeatingAcceptedJury() {
		var report = PrReviewDecisionExample.run(directory.resolve("runs"));
		assertThat(report.subject()).isEqualTo("example/repository#42");
		assertThat(report.text()).contains("Full report", "Review needs more evidence", "fixture-revision-42",
				"INCONCLUSIVE", "PASS: Citation covered", "ERROR: Fixture: security provider unavailable");
	}

}
