package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.VerdictReading;
import org.junit.jupiter.api.Test;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static org.assertj.core.api.Assertions.assertThat;

class ProductionCorpusTest {
    record Request() {}
    record RemoteTask() {}
    record CancelReceipt() {}
    record Pet() {}
    record PendingOrder() {}
    record CreditResult() {}
    record Reply() {}
    record Topic() {}
    record Outline() {}
    record Story() {}
    record StoryInput(Topic topic, Outline outline) {}
    record StoredRequest() {}
    record Translation() {}
    record TranslationToStore(Request request, Translation translation) {}
    record PreparedTranslation() {}
    record Message() {}
    record TriageResult() {}
    record PickerInput(Message original, List<Translation> candidates) {}
    record SelectedTranslation() {}
    record TaskReport() {}
    record RepairReport() {}
    record CycleReceipt() {}
    record Task1Result() {}
    record TaskReceipt() {}
    record SubResult() {}
    record SearchResults() {}
    record Rollup() {}
    record CounterBoard() {}
    record CounterPatch() {}
    record PatchInput(CounterBoard board, CounterPatch patch) {}
    record BoardUpdate(CounterBoard board, List<CounterPatch> patches) {}
    record ReadyOrder() {}
    record Invoice() {}
    record PaymentInput(ReadyOrder request, Invoice invoice) {}
    record PaymentReceipt() {}
    record StockResult() {}
    record ProductionPlan() {}
    record Goods() {}
    record FilledOrder() {}
    record ShippingInput(PaymentReceipt payment, FilledOrder goods) {}
    record Shipment() {}
    record ExportRequest() {}
    record Token() {}
    record Lease() {}
    record PipelineRun() {}
    record PipelineStatus() {}
    record ProcessInput(ExportRequest request, PipelineRun run) {}
    record ExportAttempt() {}
    record TokenReturn() {}
    record ExportReplyInput(ExportRequest request, TokenReturn returned, ExportAttempt attempt) {}
    record ExportReply() {}
    record JobSettings() {}
    record LoadCreation() {}
    record QueryCreation() {}
    record LoadInput(LoadCreation creation, JobSettings settings) {}
    record QueryInput(QueryCreation creation, JobSettings settings) {}
    record JobRequest() {}
    record JobResult() {}
    record SubmitState() {}
    record PollState() {}
    record FastaFile() {}
    record Chunk() {}
    record BlastResult() {}
    record BlastFile() {}
    record LogFile() {}
    record BlastArtifacts(BlastFile results, LogFile logs) {}
    record ArtifactBundle() {}
    record Packet() {}
    record PreparedPacket() {}
    record DispatchReceipt() {}
    record DispatchReport() {}
    record DataRecord() {}
    record ProcessedRecord() {}
    record Count() {}
    record Laundry() {}
    record WashedLaundry() {}
    record RinsedLaundry() {}
    record DryLaundry() {}
    record StoryRequest() {}
    record StoryDecisionInput(StoryRequest request, Outline outline, Interpretation reading) {}
    record RevisionState() {}
    record GeneratedRevision() {}
    record RevisionInput(GeneratedRevision generated, Verdict evidence, Interpretation reading) {}
    record PrRequest() {}
    record PrContext() {}
    record RebaseResult() {}
    record ConflictReport() {}
    record BuildResult() {}
    record RepairInput(RebaseResult rebase, ConflictReport conflicts, BuildResult build) {}
    record FixInput(BuildResult build, ConflictReport conflicts) {}
    record FixResult() {}
    record CleanupReceipt() {}
    record QualityAssessment() {}
    record BackportAssessment() {}
    record FullReportInput(QualityAssessment quality, BackportAssessment backport, PrContext context,
            Verdict evidence, Interpretation reading) {}
    record EarlyReportInput(PrContext context, Interpretation reading, BuildResult build) {}
    record ReviewReport() {}
    record FirstResult() {}
    record SecondInput(FirstResult first, Request original) {}
    record SecondResult() {}
    record ThirdResult() {}
    record FourthResult() {}
    record FifthInput(FourthResult latest, SecondInput earlier) {}
    record FinalResult() {}


    enum Credit { RESERVED, UNAVAILABLE }
    enum Language { FRENCH, SPANISH, ENGLISH, ANSWERED }
    enum TaskStatus { ALL_OK, NEEDS_RECOVERY }
    enum RepairStatus { REPAIRED, NEEDS_MANUAL }
    enum Flag { YES, NO }
    enum LastCounter { FIRST, SECOND }
    enum Production { MAKE, FROM_STOCK }
    enum Namespace { DISABLED, ENABLED }
    enum ExportExit { DISABLED, FINISHED, CAPPED }
    enum Submission { NOT_STARTED, STARTED }
    enum LoadCreated { NO_FILES, CREATED }
    enum JobOutcome { FAILED, SUCCEEDED }
    enum QueryCreated { FAILED, CREATED }
    enum RevisionOutcome { ACCEPTED, NOT_APPLICABLE, NOT_ASSESSED }
    enum RepairChoice { REPAIR, SKIP }
    static Type list(Type t) { return new ListType(t); }
    static Call c(String name,Type in,Type out) { return new Call(name,Op.declared(name,in,out)); }
    static End end() { return new End(Terminal.SUCCEEDED,""); }
    static End fail() { return new End(Terminal.FAILED,"assessment-failed"); }
    static Arm a(Enum<?> route,Node... body) { return new Arm(route,List.of(body)); }
    static Choice choose(String id,Type input,Class<? extends Enum<?>> domain,Arm... arms) {
        return new Choice(id,Op.declared(id,input,domain),null,List.of(arms));
    }
    static Choice judge(String id,Type input,Arm... arms) { return new Choice(id,null,new Assessment<>(id,input),List.of(arms)); }
    static Member member(String name,Node... body) { return new Member(name,List.of(body)); }
    static Parallel group(String name,Member... members) { return new Parallel(name,null,true,List.of(members)); }
    static Definition<?,?> definition(String name,Type input,Type output,Node... nodes) { return new Definition<>(name,input,output,List.of(nodes),java.time.Duration.ofMinutes(5)); }
    static void verify(Definition<?,?> definition,String... expected) {
        Compilation<?,?> built=StructuredWorkflowCompiler.compile(definition);
        List<String> actual=new ArrayList<>();
        for(Binding binding:built.bindings()) actual.add(binding.operation()+phase(binding.phase())+"="+binding.expression());
        for(Product product:built.products()) {
            actual.add("product:"+product.result().display()+phase(product.result().identity().phase())+"="+
                    product.members().stream().map(Fact::display).collect(java.util.stream.Collectors.joining(",")));
            if("forEach".equals(built.metadata().get(product.placement()).kind())) {
                java.util.Set<Fact> ancestry=new java.util.LinkedHashSet<>();
                product.members().forEach(f->ancestors(f,ancestry));
                List<Fact> items=ancestry.stream().filter(f->f.identity().role().equals("item")).toList();
                assertThat(items).hasSize(1);
                Fact item=items.getFirst();
                actual.add("manifest:"+item.display()+"="+item.consumed().getFirst().display());
            }
        }
        for(Capture capture:built.captures()) actual.add("capture:"+capture.result().display()+phase(capture.result().identity().phase())+"="+
                capture.alternatives().stream().map(f->f.display()+phase(f.identity().phase())).collect(java.util.stream.Collectors.joining(",")));
        built.metadata().values().stream().filter(m->m.kind().equals("terminal")).forEach(m->{
            String intent=m.configuration().get("intent");
            actual.add(intent.equals("SUCCEEDED")?"end="+m.configuration().get("value"):"end:"+intent);
        });
        assertThat(actual).as(definition.name()+" complete independent mapping set")
                .containsExactlyInAnyOrder(expected);
        assertThat(built.input()).isEqualTo(definition.input());
        assertThat(built.output()).isEqualTo(definition.output());
        assertThat(built.graph().nodes()).isNotEmpty();
    }
    static String phase(String phase) { return phase.equals("root")?"":"@"+phase; }
    static void ancestors(Fact value,java.util.Set<Fact> result) {
        if(!result.add(value)) return;
        value.components().forEach(f->ancestors(f,result)); value.consumed().forEach(f->ancestors(f,result));
    }
    @Test void remoteCancellation() {
        verify(definition("remote",Request.class,CancelReceipt.class,
            c("start",Request.class,RemoteTask.class),c("cancel",RemoteTask.class,CancelReceipt.class),end()),
            "start=root","cancel=start.out","end=cancel.out");
    }
    @Test void opaqueBoundedRequest() {
        verify(definition("request",Request.class,Pet.class,c("fetch",Request.class,Pet.class),end()),"fetch=root","end=fetch.out");
    }
    @Test void creditPaths() {
        verify(definition("credit",Request.class,Reply.class,
            c("create",Request.class,PendingOrder.class),c("reserve",PendingOrder.class,CreditResult.class),
            choose("credit",CreditResult.class,Credit.class,
                a(Credit.RESERVED,c("approve",PendingOrder.class,Reply.class),end()),
                a(Credit.UNAVAILABLE,c("reject",PendingOrder.class,Reply.class),end()))),
            "create=root","reserve=create.out","credit=reserve.out","approve=create.out","reject=create.out","end=approve.out","end=reject.out");
    }
    @Test void storyBody() {
        verify(definition("story",Topic.class,Story.class,c("outline",Topic.class,Outline.class),c("write",StoryInput.class,Story.class),end()),
            "outline=root","write=StoryInput(root,outline.out)","end=write.out");
    }
    @Test void translationPreparation() {
        verify(definition("preparation",Request.class,PreparedTranslation.class,
            c("store",Request.class,StoredRequest.class),c("translate",Request.class,Translation.class),c("save",TranslationToStore.class,PreparedTranslation.class),end()),
            "store=root","translate=root","save=TranslationToStore(root,translate.out)","end=save.out");
    }
    @Test void languageHandoff() {
        verify(definition("languages",Message.class,Reply.class,c("triage",Message.class,TriageResult.class),
            choose("language",TriageResult.class,Language.class,
                a(Language.FRENCH,c("french",TriageResult.class,Reply.class),end()),
                a(Language.SPANISH,c("spanish",TriageResult.class,Reply.class),end()),
                a(Language.ENGLISH,c("english",TriageResult.class,Reply.class),end()),
                a(Language.ANSWERED,c("answered",TriageResult.class,Reply.class),end()))),
            "triage=root","language=triage.out","french=triage.out","spanish=triage.out","english=triage.out","answered=triage.out",
            "end=french.out","end=spanish.out","end=english.out","end=answered.out");
    }
    @Test void parallelTranslations() {
        verify(definition("translations",Message.class,SelectedTranslation.class,
            group("candidates",member("first",c("first",Message.class,Translation.class)),member("second",c("second",Message.class,Translation.class)),member("third",c("third",Message.class,Translation.class))),
            c("pick",PickerInput.class,SelectedTranslation.class),end()),
            "product:candidates.aggregate=first.out,second.out,third.out","first=root","second=root","third=root","pick=PickerInput(root,candidates.aggregate)","end=pick.out");
    }
    @Test void automaticTaskCycle() {
        verify(definition("tasks",Request.class,CycleReceipt.class,
            group("tasks",member("one",c("one",Request.class,TaskReport.class)),member("two",c("two",Request.class,TaskReport.class)),member("three",c("three",Request.class,TaskReport.class))),
            choose("status",list(TaskReport.class),TaskStatus.class,
                a(TaskStatus.ALL_OK,c("complete",Request.class,CycleReceipt.class),end()),
                a(TaskStatus.NEEDS_RECOVERY,c("repair",list(TaskReport.class),RepairReport.class),
                    choose("repaired",RepairReport.class,RepairStatus.class,
                        a(RepairStatus.REPAIRED,c("complete",Request.class,CycleReceipt.class),end()),
                        a(RepairStatus.NEEDS_MANUAL,new End(Terminal.CANCELLED,"manual-repair-required")))))),
            "product:tasks.aggregate=one.out,two.out,three.out","one=root","two=root","three=root","status=tasks.aggregate","complete=root","repair=tasks.aggregate","repaired=repair.out","complete=root",
            "end=complete.out","end=complete.out","end:CANCELLED");
    }
    @Test void staticChildLimb() {
        Definition<?,?> child=definition("processor",Task1Result.class,SubResult.class,c("process",Task1Result.class,SubResult.class),end());
        verify(definition("search",Task1Result.class,Rollup.class,
            group("fork",member("ten",c("ten",Task1Result.class,TaskReceipt.class),new Child("childA",child)),
                member("eleven",c("eleven",Task1Result.class,TaskReceipt.class),new Child("childB",child))),
            c("search",Task1Result.class,SearchResults.class),c("rollup",SearchResults.class,Rollup.class),end()),
            "product:fork.aggregate=childA.out,childB.out","ten=root","childA=root","eleven=root","childB=root","search=root","rollup=search.out","end=rollup.out");
    }
    @Test void conditionalIndependentCounters() {
        verify(definition("counters",CounterBoard.class,CounterBoard.class,
            c("one",CounterBoard.class,CounterBoard.class),c("two",CounterBoard.class,CounterBoard.class),
            choose("extra",CounterBoard.class,Flag.class,
                a(Flag.YES,group("patches",member("three-four",c("three",CounterBoard.class,CounterPatch.class),c("four",PatchInput.class,CounterPatch.class)),
                    member("five",c("five",CounterBoard.class,CounterPatch.class)),member("ten",c("ten",CounterBoard.class,CounterPatch.class))),c("apply",BoardUpdate.class,CounterBoard.class)),a(Flag.NO)),
            c("six",CounterBoard.class,CounterBoard.class),choose("final",CounterBoard.class,LastCounter.class,
                a(LastCounter.FIRST,c("seven",CounterBoard.class,CounterBoard.class),end()),a(LastCounter.SECOND,c("eight",CounterBoard.class,CounterBoard.class),end()))),
            "product:patches.aggregate=four.out,five.out,ten.out","capture:extra.capture<CounterBoard>=apply.out,two.out","one=root","two=one.out","extra=two.out","three=two.out","four=PatchInput(two.out,three.out)","five=two.out","ten=two.out",
            "apply=BoardUpdate(two.out,patches.aggregate)","six=extra.capture<CounterBoard>","final=six.out","seven=six.out","eight=six.out","end=seven.out","end=eight.out");
    }
    @Test void readyOrderFulfillment() {
        verify(definition("shipping",ReadyOrder.class,Shipment.class,
            group("order",member("billing",c("bill",ReadyOrder.class,Invoice.class),c("payment",PaymentInput.class,PaymentReceipt.class)),
                member("fulfillment",c("stock",ReadyOrder.class,StockResult.class),
                    choose("goods",StockResult.class,Production.class,
                        a(Production.MAKE,c("plan",StockResult.class,ProductionPlan.class),c("produce",ProductionPlan.class,Goods.class)),
                        a(Production.FROM_STOCK,c("useStock",StockResult.class,Goods.class))),c("fill",Goods.class,FilledOrder.class))),
            c("ship",ShippingInput.class,Shipment.class),end()),
            "product:order.product=payment.out,fill.out","capture:goods.capture<Goods>=produce.out,useStock.out","bill=root","payment=PaymentInput(root,bill.out)","stock=root","goods=stock.out","plan=stock.out","produce=plan.out","useStock=stock.out",
            "fill=goods.capture<Goods>","ship=ShippingInput(payment.out,fill.out)","end=ship.out");
    }
    static Definition<?,?> exportAttempt() {
        return definition("attempt",ExportRequest.class,ExportAttempt.class,c("start",ExportRequest.class,PipelineRun.class),
            choose("namespace",PipelineRun.class,Namespace.class,
                a(Namespace.DISABLED,c("disabled",PipelineRun.class,ExportAttempt.class),end()),
                a(Namespace.ENABLED,c("poll",PipelineRun.class,PipelineStatus.class),c("process",ProcessInput.class,ExportAttempt.class),end())));
    }
    @Test void exportChildAttempt() {
        verify(exportAttempt(),"start=root","namespace=start.out","disabled=start.out","poll=start.out","process=ProcessInput(root,start.out)","end=disabled.out","end=process.out");
    }
    @Test void boundedExportParent() {
        verify(definition("export",ExportRequest.class,ExportReply.class,c("reserve",ExportRequest.class,Token.class),c("acquire",Token.class,Lease.class),
            new Loop("export",Op.named("stop",ExportAttempt.class,Boolean.class),3,LimitPolicy.EXIT,List.of(new Child("attempt",exportAttempt()))),
            c("return",Token.class,TokenReturn.class),choose("exit",ExportAttempt.class,ExportExit.class,
                a(ExportExit.DISABLED,c("disabled",ExportReplyInput.class,ExportReply.class),end()),
                a(ExportExit.FINISHED,c("finished",ExportReplyInput.class,ExportReply.class),end()),
                a(ExportExit.CAPPED,c("capped",ExportReplyInput.class,ExportReply.class),end()))),
            "reserve=root","acquire=reserve.out","attempt@root/first=root","stop@root/first=attempt.out","attempt@root/carried=root","stop@root/carried=attempt.out","return=reserve.out","exit=export.result",
            "disabled=ExportReplyInput(root,return.out,export.result)","finished=ExportReplyInput(root,return.out,export.result)","capped=ExportReplyInput(root,return.out,export.result)",
            "end=disabled.out","end=finished.out","end=capped.out");
    }
    static Definition<?,?> jobChild() {
        return definition("job",JobRequest.class,JobResult.class,c("submit",JobRequest.class,SubmitState.class),
            choose("submitted",SubmitState.class,Submission.class,
                a(Submission.NOT_STARTED,c("submission",SubmitState.class,JobResult.class),end()),
                a(Submission.STARTED,new Loop("poll",Op.named("finished",PollState.class,Boolean.class),3,LimitPolicy.FAIL,
                    List.of(new Timer("spacing",Duration.ofSeconds(5)),c("poll",JobRequest.class,PollState.class))),c("result",PollState.class,JobResult.class),end())));
    }
    @Test void pollingJobChild() {
        verify(jobChild(),"submit=root","submitted=submit.out","submission=submit.out","poll@root/first=root","finished@root/first=poll.out","poll@root/carried=root","finished@root/carried=poll.out",
            "result=poll.result","end=submission.out","end=result.out");
    }
    @Test void loadAndQueryParent() {
        verify(definition("jobs",JobSettings.class,Reply.class,c("loadCreation",JobSettings.class,LoadCreation.class),
            choose("created",LoadCreation.class,LoadCreated.class,
                a(LoadCreated.NO_FILES,c("noFiles",JobSettings.class,Reply.class),end()),
                a(LoadCreated.CREATED,c("loadRequest",LoadInput.class,JobRequest.class),new Child("loadChild",jobChild()),
                    choose("loaded",JobResult.class,JobOutcome.class,
                        a(JobOutcome.FAILED,c("loadFailure",JobSettings.class,Reply.class),end()),
                        a(JobOutcome.SUCCEEDED,c("queryCreation",JobSettings.class,QueryCreation.class),
                            choose("queryCreated",QueryCreation.class,QueryCreated.class,
                                a(QueryCreated.FAILED,c("queryFailure",JobSettings.class,Reply.class),end()),
                                a(QueryCreated.CREATED,c("queryRequest",QueryInput.class,JobRequest.class),new Child("queryChild",jobChild()),
                                    choose("queried",JobResult.class,JobOutcome.class,
                                        a(JobOutcome.FAILED,c("queryFailure",JobSettings.class,Reply.class),end()),
                                        a(JobOutcome.SUCCEEDED,c("done",JobSettings.class,Reply.class),end()))))))))),
            "loadCreation=root","created=loadCreation.out","noFiles=root","loadRequest=LoadInput(loadCreation.out,root)","loadChild=loadRequest.out","loaded=loadChild.out","loadFailure=root",
            "queryCreation=root","queryCreated=queryCreation.out","queryFailure=root","queryRequest=QueryInput(queryCreation.out,root)","queryChild=queryRequest.out","queried=queryChild.out","queryFailure=root","done=root",
            "end=noFiles.out","end=loadFailure.out","end=queryFailure.out","end=queryFailure.out","end=done.out");
    }
    @Test void blastScatterGather() {
        verify(definition("scatter",FastaFile.class,ArtifactBundle.class,c("split",FastaFile.class,list(Chunk.class)),
            new Fan("chunks",Chunk.class,40,40,true,List.of(c("blast",Chunk.class,BlastResult.class))),
            group("files",member("hits",c("hits",list(BlastResult.class),BlastFile.class)),member("logs",c("logs",list(BlastResult.class),LogFile.class))),
            c("assemble",BlastArtifacts.class,ArtifactBundle.class),end()),
            "product:chunks.aggregate=blast.out","manifest:chunks.item=split.out","product:files.product=hits.out,logs.out","split=root","blast=chunks.item","hits=chunks.aggregate","logs=chunks.aggregate","assemble=BlastArtifacts(hits.out,logs.out)","end=assemble.out");
    }
    @Test void packetDispatch() {
        verify(definition("packets",Request.class,DispatchReport.class,c("generate",Request.class,list(Packet.class)),
            new Fan("packets",Packet.class,10,8,true,List.of(c("prepare",Packet.class,PreparedPacket.class),c("send",PreparedPacket.class,DispatchReceipt.class))),
            c("report",list(DispatchReceipt.class),DispatchReport.class),end()),
            "product:packets.aggregate=send.out","manifest:packets.item=generate.out","generate=root","prepare=packets.item","send=prepare.out","report=packets.aggregate","end=report.out");
    }
    @Test void finiteRecordBatch() {
        Definition<?,?> child=definition("record",DataRecord.class,ProcessedRecord.class,c("process",DataRecord.class,ProcessedRecord.class),end());
        verify(definition("batch",Request.class,Count.class,c("load",Request.class,list(DataRecord.class)),
            new Fan("records",DataRecord.class,10,2,true,List.of(new Child("process",child))),c("count",list(ProcessedRecord.class),Count.class),end()),
            "product:records.aggregate=process.out","manifest:records.item=load.out","load=root","process=records.item","count=records.aggregate","end=count.out");
    }
    @Test void automaticWashCycle() {
        verify(definition("wash",Laundry.class,DryLaundry.class,c("wash",Laundry.class,WashedLaundry.class),c("rinse",WashedLaundry.class,RinsedLaundry.class),c("dry",RinsedLaundry.class,DryLaundry.class),end()),
            "wash=root","rinse=wash.out","dry=rinse.out","end=dry.out");
    }
    @Test void judgedStory() {
        verify(definition("judgedStory",StoryRequest.class,Reply.class,c("outline",StoryRequest.class,Outline.class),judge("judge",Outline.class,
            a(VerdictReading.ACCEPTED,c("write",Outline.class,Reply.class),end()),
            a(VerdictReading.REJECTED,c("rejected",StoryDecisionInput.class,Reply.class),end()),
            a(VerdictReading.UNDECIDED,c("undecided",StoryDecisionInput.class,Reply.class),end()),
            a(VerdictReading.NOT_APPLICABLE,c("inapplicable",StoryDecisionInput.class,Reply.class),end()),
            a(VerdictReading.NOT_ASSESSED,fail()))),
            "outline=root","judge=outline.out","judgeReading=judge.out","write=outline.out",
            "rejected=StoryDecisionInput(root,outline.out,judgeReading.out)","undecided=StoryDecisionInput(root,outline.out,judgeReading.out)","inapplicable=StoryDecisionInput(root,outline.out,judgeReading.out)",
            "end=write.out","end=rejected.out","end=undecided.out","end=inapplicable.out","end:FAILED");
    }
    @Test void evaluatorOptimizer() {
        Choice review=judge("review",GeneratedRevision.class,
            a(VerdictReading.ACCEPTED,c("accepted",RevisionInput.class,RevisionState.class)),
            a(VerdictReading.REJECTED,c("rejected",RevisionInput.class,RevisionState.class)),
            a(VerdictReading.UNDECIDED,c("undecided",RevisionInput.class,RevisionState.class)),
            a(VerdictReading.NOT_APPLICABLE,c("inapplicable",RevisionInput.class,RevisionState.class)),
            a(VerdictReading.NOT_ASSESSED,c("unassessed",RevisionInput.class,RevisionState.class)));
        verify(definition("revision",StoryRequest.class,Reply.class,c("start",StoryRequest.class,RevisionState.class),
            new Loop("revision",Op.named("complete",RevisionState.class,Boolean.class),5,LimitPolicy.FAIL,
                List.of(c("generate",RevisionState.class,GeneratedRevision.class),review)),
            choose("outcome",RevisionState.class,RevisionOutcome.class,
                a(RevisionOutcome.ACCEPTED,c("finalOutline",RevisionState.class,Reply.class),end()),
                a(RevisionOutcome.NOT_APPLICABLE,c("noApplicable",RevisionState.class,Reply.class),end()),
                a(RevisionOutcome.NOT_ASSESSED,fail()))),
            "capture:review.capture<RevisionState>@root/first=accepted.out@root/first,rejected.out@root/first,undecided.out@root/first,inapplicable.out@root/first,unassessed.out@root/first",
            "capture:review.capture<RevisionState>@root/carried=accepted.out@root/carried,rejected.out@root/carried,undecided.out@root/carried,inapplicable.out@root/carried,unassessed.out@root/carried",
            "start=root","generate@root/first=start.out","review@root/first=generate.out","reviewReading@root/first=review.out",
            "accepted@root/first=RevisionInput(generate.out,review.out,reviewReading.out)","rejected@root/first=RevisionInput(generate.out,review.out,reviewReading.out)",
            "undecided@root/first=RevisionInput(generate.out,review.out,reviewReading.out)","inapplicable@root/first=RevisionInput(generate.out,review.out,reviewReading.out)","unassessed@root/first=RevisionInput(generate.out,review.out,reviewReading.out)","complete@root/first=review.capture<RevisionState>",
            "generate@root/carried=revision.result","review@root/carried=generate.out","reviewReading@root/carried=review.out",
            "accepted@root/carried=RevisionInput(generate.out,review.out,reviewReading.out)","rejected@root/carried=RevisionInput(generate.out,review.out,reviewReading.out)",
            "undecided@root/carried=RevisionInput(generate.out,review.out,reviewReading.out)","inapplicable@root/carried=RevisionInput(generate.out,review.out,reviewReading.out)","unassessed@root/carried=RevisionInput(generate.out,review.out,reviewReading.out)","complete@root/carried=review.capture<RevisionState>",
            "outcome=revision.result","finalOutline=revision.result","noApplicable=revision.result","end=finalOutline.out","end=noApplicable.out","end:FAILED");
    }
    @Test void reviewRepairConvergence() {
        verify(definition("review",PrRequest.class,ReviewReport.class,
            c("fetch",PrRequest.class,PrContext.class),c("rebase",PrContext.class,RebaseResult.class),c("conflicts",RebaseResult.class,ConflictReport.class),c("test",ConflictReport.class,BuildResult.class),
            choose("build",RepairInput.class,RepairChoice.class,a(RepairChoice.REPAIR,c("fix",FixInput.class,FixResult.class),c("retest",ConflictReport.class,BuildResult.class)),a(RepairChoice.SKIP)),
            c("cleanup",PrContext.class,CleanupReceipt.class),judge("judge",BuildResult.class,
                a(VerdictReading.ACCEPTED,group("assess",member("quality",c("quality",PrContext.class,QualityAssessment.class)),member("backport",c("backport",PrContext.class,BackportAssessment.class))),c("full",FullReportInput.class,ReviewReport.class),end()),
                a(VerdictReading.REJECTED,c("early",EarlyReportInput.class,ReviewReport.class),end()),
                a(VerdictReading.UNDECIDED,c("unresolved",EarlyReportInput.class,ReviewReport.class),end()),
                a(VerdictReading.NOT_APPLICABLE,c("inapplicable",EarlyReportInput.class,ReviewReport.class),end()),a(VerdictReading.NOT_ASSESSED,fail()))),
            "product:assess.product=quality.out,backport.out","capture:build.capture<BuildResult>=retest.out,test.out","fetch=root","rebase=fetch.out","conflicts=rebase.out","test=conflicts.out","build=RepairInput(rebase.out,conflicts.out,test.out)","fix=FixInput(test.out,conflicts.out)","retest=conflicts.out","cleanup=fetch.out",
            "judge=build.capture<BuildResult>","judgeReading=judge.out","quality=fetch.out","backport=fetch.out","full=FullReportInput(quality.out,backport.out,fetch.out,judge.out,judgeReading.out)",
            "early=EarlyReportInput(fetch.out,judgeReading.out,build.capture<BuildResult>)","unresolved=EarlyReportInput(fetch.out,judgeReading.out,build.capture<BuildResult>)","inapplicable=EarlyReportInput(fetch.out,judgeReading.out,build.capture<BuildResult>)",
            "end=full.out","end=early.out","end=unresolved.out","end=inapplicable.out","end:FAILED");
    }
    @Test void exactEarlierInput() {
        verify(definition("savedInput",Request.class,FinalResult.class,c("first",Request.class,FirstResult.class),c("second",SecondInput.class,SecondResult.class),
            c("third",SecondResult.class,ThirdResult.class),c("fourth",ThirdResult.class,FourthResult.class),c("fifth",FifthInput.class,FinalResult.class),end()),
            "first=root","second=SecondInput(first.out,root)","third=second.out","fourth=third.out","fifth=FifthInput(fourth.out,second.in)","end=fifth.out");
    }
}
