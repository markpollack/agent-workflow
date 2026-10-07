package io.github.markpollack.workflow.flows.r1probe.grammar;

import java.util.List;

import io.github.markpollack.judge.verdict.Verdict.Conclusion;

import io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.Assessment;
import io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.Operation;
import io.github.markpollack.workflow.flows.r1probe.grammar.GrammarPrototype.TypeRef;

public final class GrammarFixtures {
    private GrammarFixtures() {}

    public record Request(String value) {}
    public record First(String value) {}
    public record SecondInput(First first, Request root) {}
    public record Second(String value) {}
    public record Third(String value) {}
    public record Fourth(String value) {}
    public record FinalInput(Fourth latest, SecondInput saved) {}
    public record Reply(String value) {}
    public record OrderRequest(String value) {}
    public record Pending(String value) {}
    public record Credit(String value) {}
    public record OrderReply(String value) {}
    public enum CreditOutcome { RESERVED, UNAVAILABLE }
    public enum ForeignOutcome { RESERVED }
    public record StoryRequest(String value) {}
    public record Outline(String value) {}
    public record StoryReply(String value) {}
    public record AssessmentContext(StoryRequest request, Outline outline) {}
    public record AssessedReplyInput(StoryRequest request, Outline outline, Conclusion interpretation) {}
    public record DraftRequest(String value) {}
    public record Draft(String value) {}
    public record Published(String value) {}
    public record Source(String value) {}
    public record Chunk(String value) {}
    public record Result(String value) {}
    public record ResultFile(String value) {}
    public record LogFile(String value) {}
    public record Products(ResultFile result, LogFile log) {}
    public record Bundle(String value) {}
    public record JobRequest(String value) {}
    public record JobHandle(String value) {}
    public record JobStatus(String value) {}
    public record JobReport(String value) {}

    public static final Operation<Request, First> first = Operation.named("first", Request.class, First.class);
    public static final Operation<SecondInput, Second> second = Operation.named("second", SecondInput.class, Second.class);
    public static final Operation<Second, Third> third = Operation.named("third", Second.class, Third.class);
    public static final Operation<Third, Fourth> fourth = Operation.named("fourth", Third.class, Fourth.class);
    public static final Operation<FinalInput, Reply> fifth = Operation.named("fifth", FinalInput.class, Reply.class);
    public static final Operation<OrderRequest, Pending> createOrder = Operation.named("create", OrderRequest.class, Pending.class);
    public static final Operation<Pending, Credit> reserve = Operation.named("reserve", Pending.class, Credit.class);
    public static final Operation<Credit, CreditOutcome> credit = Operation.named("credit", Credit.class, CreditOutcome.class);
    public static final Operation<Pending, OrderReply> approve = Operation.named("approve", Pending.class, OrderReply.class);
    public static final Operation<Pending, OrderReply> reject = Operation.named("reject", Pending.class, OrderReply.class);
    public static final Operation<StoryRequest, Outline> outline = Operation.named("outline", StoryRequest.class, Outline.class);
    public static final Assessment<AssessmentContext> jury = new Assessment<>("jury", AssessmentContext.class);
    public static final Operation<Outline, StoryReply> story = Operation.named("story", Outline.class, StoryReply.class);
    public static final Operation<AssessedReplyInput, StoryReply> rejectionReply = Operation.named("rejectionReply", AssessedReplyInput.class, StoryReply.class);
    public static final Operation<AssessedReplyInput, StoryReply> unresolvedReply = Operation.named("unresolvedReply", AssessedReplyInput.class, StoryReply.class);
    public static final Operation<AssessedReplyInput, StoryReply> notApplicableReply = Operation.named("notApplicableReply", AssessedReplyInput.class, StoryReply.class);
    public static final Operation<DraftRequest, Draft> initial = Operation.named("initial", DraftRequest.class, Draft.class);
    public static final Operation<Draft, Draft> revise = Operation.named("revise", Draft.class, Draft.class);
    public static final Operation<Draft, Boolean> ready = Operation.named("ready", Draft.class, Boolean.class);
    public static final Operation<Draft, Published> publish = Operation.named("publish", Draft.class, Published.class);
    public static final Operation<Source, List<Chunk>> split = Operation.named("split", new TypeRef<Source>() {}, new TypeRef<List<Chunk>>() {});
    public static final Operation<Chunk, Result> process = Operation.named("process", Chunk.class, Result.class);
    public static final Operation<List<Result>, ResultFile> results = Operation.named("results", new TypeRef<List<Result>>() {}, new TypeRef<ResultFile>() {});
    public static final Operation<List<Result>, LogFile> logs = Operation.named("logs", new TypeRef<List<Result>>() {}, new TypeRef<LogFile>() {});
    public static final Operation<Products, Bundle> assemble = Operation.named("assemble", Products.class, Bundle.class);
    public static final Operation<JobRequest, JobHandle> submit = Operation.named("submit", JobRequest.class, JobHandle.class);
    public static final Operation<JobHandle, JobStatus> inspect = Operation.named("inspect", JobHandle.class, JobStatus.class);
    public static final Operation<JobStatus, JobReport> report = Operation.named("report", JobStatus.class, JobReport.class);
}
