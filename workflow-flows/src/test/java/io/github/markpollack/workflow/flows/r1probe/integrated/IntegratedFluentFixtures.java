package io.github.markpollack.workflow.flows.r1probe.integrated;

import java.util.List;

import io.github.markpollack.judge.jury.interpretation.Interpretation;

import io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedModel.Assessment;
import io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedModel.Op;
import io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedModel.TypeRef;

public final class IntegratedFluentFixtures {
    private IntegratedFluentFixtures() {}

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
    public record AssessedReplyInput(StoryRequest request, Outline outline, Interpretation interpretation) {}
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

    public static final Op<Request, First> first = Op.named("first", Request.class, First.class);
    public static final Op<SecondInput, Second> second = Op.named("second", SecondInput.class, Second.class);
    public static final Op<Second, Third> third = Op.named("third", Second.class, Third.class);
    public static final Op<Third, Fourth> fourth = Op.named("fourth", Third.class, Fourth.class);
    public static final Op<FinalInput, Reply> fifth = Op.named("fifth", FinalInput.class, Reply.class);
    public static final Op<OrderRequest, Pending> createOrder = Op.named("create", OrderRequest.class, Pending.class);
    public static final Op<Pending, Credit> reserve = Op.named("reserve", Pending.class, Credit.class);
    public static final Op<Credit, CreditOutcome> credit = Op.named("credit", Credit.class, CreditOutcome.class);
    public static final Op<Pending, OrderReply> approve = Op.named("approve", Pending.class, OrderReply.class);
    public static final Op<Pending, OrderReply> reject = Op.named("reject", Pending.class, OrderReply.class);
    public static final Op<StoryRequest, Outline> outline = Op.named("outline", StoryRequest.class, Outline.class);
    public static final Assessment<AssessmentContext> jury = new Assessment<>("jury", AssessmentContext.class);
    public static final Op<Outline, StoryReply> story = Op.named("story", Outline.class, StoryReply.class);
    public static final Op<AssessedReplyInput, StoryReply> rejectionReply = Op.named("rejectionReply", AssessedReplyInput.class, StoryReply.class);
    public static final Op<AssessedReplyInput, StoryReply> unresolvedReply = Op.named("unresolvedReply", AssessedReplyInput.class, StoryReply.class);
    public static final Op<AssessedReplyInput, StoryReply> notApplicableReply = Op.named("notApplicableReply", AssessedReplyInput.class, StoryReply.class);
    public static final Op<DraftRequest, Draft> initial = Op.named("initial", DraftRequest.class, Draft.class);
    public static final Op<Draft, Draft> revise = Op.named("revise", Draft.class, Draft.class);
    public static final Op<Draft, Boolean> ready = Op.named("ready", Draft.class, Boolean.class);
    public static final Op<Draft, Published> publish = Op.named("publish", Draft.class, Published.class);
    public static final Op<Source, List<Chunk>> split = Op.named("split", new TypeRef<Source>() {}, new TypeRef<List<Chunk>>() {});
    public static final Op<Chunk, Result> process = Op.named("process", Chunk.class, Result.class);
    public static final Op<List<Result>, ResultFile> results = Op.named("results", new TypeRef<List<Result>>() {}, new TypeRef<ResultFile>() {});
    public static final Op<List<Result>, LogFile> logs = Op.named("logs", new TypeRef<List<Result>>() {}, new TypeRef<LogFile>() {});
    public static final Op<Products, Bundle> assemble = Op.named("assemble", Products.class, Bundle.class);
    public static final Op<JobRequest, JobHandle> submit = Op.named("submit", JobRequest.class, JobHandle.class);
    public static final Op<JobHandle, JobStatus> inspect = Op.named("inspect", JobHandle.class, JobStatus.class);
    public static final Op<JobStatus, JobReport> report = Op.named("report", JobStatus.class, JobReport.class);
}
