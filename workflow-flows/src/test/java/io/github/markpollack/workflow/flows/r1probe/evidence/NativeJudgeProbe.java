package io.github.markpollack.workflow.flows.r1probe.evidence;

import java.util.List;
import java.util.Map;

import io.github.markpollack.judge.Judge;
import io.github.markpollack.judge.JudgeMetadata;
import io.github.markpollack.judge.JudgeType;
import io.github.markpollack.judge.JudgeWithMetadata;
import io.github.markpollack.judge.Judges;
import io.github.markpollack.judge.context.JudgmentContext;
import io.github.markpollack.judge.jury.CascadedJury;
import io.github.markpollack.judge.jury.ConsensusStrategy;
import io.github.markpollack.judge.jury.ErrorPolicy;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.jury.NotApplicablePolicy;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.jury.TierPolicy;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.VotingStrategy;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.ReadingSupport;
import io.github.markpollack.judge.jury.interpretation.VerdictReading;
import io.github.markpollack.judge.jury.interpretation.Verdicts;
import io.github.markpollack.judge.result.Check;
import io.github.markpollack.judge.result.Judgment;

final class NativeJudgeProbe {

	static final JudgmentContext CONTEXT = JudgmentContext.builder().goal("Check a generated report").build();

	record Assessment(Verdict verdict, Interpretation interpretation, VerdictReading route, String failure) {
	}

	static Assessment assess(Jury jury, JudgmentContext context) {
		Verdict verdict;
		try {
			verdict = jury.vote(context);
		}
		catch (RuntimeException ex) {
			return new Assessment(null, null, null, ex.getClass().getName() + ": " + ex.getMessage());
		}
		Interpretation interpretation = Verdicts.interpret(verdict);
		return new Assessment(verdict, interpretation, route(interpretation), failure(interpretation));
	}

	static VerdictReading route(Interpretation interpretation) {
		return interpretation.reading() != null && interpretation.readingSupport() == ReadingSupport.SUPPORTED
				? interpretation.reading() : null;
	}

	static String failure(Interpretation interpretation) {
		if (interpretation.reading() == null) {
			return "missing native reading";
		}
		return interpretation.readingSupport() == ReadingSupport.SUPPORTED ? null
				: "unsupported native reading: " + interpretation.readingSupport();
	}

	static Jury jury(VerdictReading reading) {
		Judgment judgment = switch (reading) {
			case ACCEPTED -> Judgment.pass("report meets rubric").toBuilder()
				.check(Check.pass("sections", "all required sections present"))
				.metadata("citations", List.of(Map.of("file", "report.md", "lines", List.of(2, 7))))
				.build();
			case REJECTED -> Judgment.fail("required section absent");
			case UNDECIDED -> Judgment.abstain("evidence does not resolve the rubric");
			case NOT_APPLICABLE -> Judgment.notApplicable("no report to assess");
			case NOT_ASSESSED -> Judgment.error("index unavailable");
		};
		Judge judge = reading == VerdictReading.NOT_APPLICABLE ? new Conditional(judgment)
				: Judges.named(context -> judgment, "rubric");
		return SimpleJury.builder().judge(judge).parallel(false)
			.votingStrategy(new ConsensusStrategy(ErrorPolicy.PROPAGATE, NotApplicablePolicy.EXCLUDE)).build();
	}

	static Jury composite() {
		return CascadedJury.builder()
			.tier("preflight", throwing(), TierPolicy.REJECT_ON_ANY_FAIL)
			.tier("rubric", jury(VerdictReading.ACCEPTED), TierPolicy.FINAL_TIER).build();
	}

	static Jury throwing() {
		return new Jury() {
			@Override
			public List<Judge> getJudges() {
				return List.of();
			}

			@Override
			public VotingStrategy getVotingStrategy() {
				return new ConsensusStrategy();
			}

			@Override
			public Verdict vote(JudgmentContext context) {
				throw new IllegalStateException("jury unavailable");
			}
		};
	}

	private record Conditional(Judgment judgment) implements JudgeWithMetadata {
		@Override
		public Judgment judge(JudgmentContext context) {
			return judgment;
		}

		@Override
		public JudgeMetadata metadata() {
			return new JudgeMetadata("rubric", "Report rubric", JudgeType.DETERMINISTIC, "no report to assess");
		}
	}

}
