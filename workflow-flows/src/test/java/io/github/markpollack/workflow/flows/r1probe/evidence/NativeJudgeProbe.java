package io.github.markpollack.workflow.flows.r1probe.evidence;
import io.github.markpollack.judge.jury.Jury;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.verdict.Verdict.Conclusion;
import io.github.markpollack.judge.judgment.Judgment;
final class NativeJudgeProbe {
    record Assessment(Verdict verdict,Conclusion route,String failure) {}
    static Assessment assess(Jury jury) {
        try {var v=jury.vote().requireUsable();return new Assessment(v,v.conclusion(),"");}
        catch(RuntimeException ex) {return new Assessment(null,null,ex.getClass().getName()+": "+ex.getMessage());}
    }
    static Jury jury(Conclusion conclusion) {
        Judgment j=switch(conclusion) {
            case PASS -> Judgment.pass("report meets rubric");
            case FAIL -> Judgment.fail("required section absent");
            case INCONCLUSIVE -> Judgment.abstain("evidence unresolved");
            case NOT_APPLICABLE -> Judgment.notApplicable("no report to assess");
        };
        return () -> Verdict.observed("rubric",j,conclusion==Conclusion.NOT_APPLICABLE?"no report":null);
    }
    static Jury composite() {return jury(Conclusion.PASS);}
    static Jury throwing() {return () -> {throw new IllegalStateException("jury unavailable");};}
}
