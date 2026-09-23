package io.github.markpollack.workflow.flows.r1probe.binding;

import java.lang.reflect.Type;
import java.util.List;

import org.junit.jupiter.api.Test;
import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.Interpretation;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.markpollack.workflow.flows.r1probe.binding.TypedPlan.*;

class MappingExamplesTest {

    static void op(Scope scope, String placement, Type input, Type output, String expected) {
        assertThat(scope.call(placement, input, output).mapping()).as(scope.name + "/" + placement).isEqualTo(expected);
    }

    static void route(Scope scope, String placement, Type input, String expected) {
        assertThat(scope.control(placement, input, Choice.class).mapping()).as(placement).isEqualTo(expected);
    }

    static void end(Scope scope, Type output, String expected) {
        assertThat(scope.terminal(output).id()).isEqualTo(expected);
    }

    static Type list(Type type) { return new ListType(type); }

    record Choice() {}
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

    @Test void remoteCancellationRetainsTaskIdentity() {
        Scope s = new Scope("remote", Request.class);
        op(s, "start", Request.class, RemoteTask.class, "root");
        op(s, "cancel", RemoteTask.class, CancelReceipt.class, "start.out");
        end(s, CancelReceipt.class, "cancel.out");
    }

    @Test void boundedOpaqueRequestHasOneDispatchBoundary() {
        Scope s = new Scope("request", Request.class);
        op(s, "fetch", Request.class, Pet.class, "root");
        end(s, Pet.class, "fetch.out");
    }

    @Test void creditRepliesUseEarlierOrderOnBothPaths() {
        Scope s = new Scope("credit", Request.class);
        op(s, "create", Request.class, PendingOrder.class, "root");
        op(s, "reserve", PendingOrder.class, CreditResult.class, "create.out");
        route(s, "credit", CreditResult.class, "reserve.out");
        for (String arm : List.of("approve", "reject")) {
            Scope p = s.fork(arm);
            op(p, arm, PendingOrder.class, Reply.class, "create.out");
            end(p, Reply.class, arm + ".out");
        }
    }

    @Test void storyCombinesRootAndOutline() {
        Scope s = new Scope("story", Topic.class);
        op(s, "outline", Topic.class, Outline.class, "root");
        op(s, "write", StoryInput.class, Story.class, "StoryInput(root,outline.out)");
        end(s, Story.class, "write.out");
    }

    @Test void preparationReusesRootAfterStorageReceipt() {
        Scope s = new Scope("preparation", Request.class);
        op(s, "store", Request.class, StoredRequest.class, "root");
        op(s, "translate", Request.class, Translation.class, "root");
        op(s, "save", TranslationToStore.class, PreparedTranslation.class, "TranslationToStore(root,translate.out)");
        end(s, PreparedTranslation.class, "save.out");
    }

    @Test void allLanguageRepliesReceiveTheTriageConversation() {
        Scope s = new Scope("languages", Message.class);
        op(s, "triage", Message.class, TriageResult.class, "root");
        route(s, "language", TriageResult.class, "triage.out");
        for (String arm : List.of("french", "spanish", "english", "answered")) {
            Scope p = s.fork(arm);
            op(p, arm, TriageResult.class, Reply.class, "triage.out");
            end(p, Reply.class, arm + ".out");
        }
    }

    @Test void translationPickerReceivesOrderedCandidatesAndOriginal() {
        Scope s = new Scope("translations", Message.class);
        Scope first = s.fork("first"), second = s.fork("second"), third = s.fork("third");
        op(third, "third", Message.class, Translation.class, "root");
        op(second, "second", Message.class, Translation.class, "root");
        op(first, "first", Message.class, Translation.class, "root");
        assertThat(s.join("candidates", first, second, third).parts()).extracting(Fact::id)
                .containsExactly("first.out", "second.out", "third.out");
        op(s, "pick", PickerInput.class, SelectedTranslation.class, "PickerInput(root,candidates.aggregate)");
        end(s, SelectedTranslation.class, "pick.out");
    }

    @Test void automaticRepairReadsAllReportsAndReceiptReadsRoot() {
        Scope s = new Scope("tasks", Request.class);
        Scope a = s.fork("one"), b = s.fork("two"), c = s.fork("three");
        op(a, "one", Request.class, TaskReport.class, "root");
        op(b, "two", Request.class, TaskReport.class, "root");
        op(c, "three", Request.class, TaskReport.class, "root");
        s.join("tasks", a, b, c);
        route(s, "status", list(TaskReport.class), "tasks.aggregate");
        Scope ok = s.fork("ok"), repair = s.fork("repair");
        op(ok, "complete", Request.class, CycleReceipt.class, "root");
        end(ok, CycleReceipt.class, "complete.out");
        op(repair, "repair", list(TaskReport.class), RepairReport.class, "tasks.aggregate");
        route(repair, "repaired", RepairReport.class, "repair.out");
        Scope fixed = repair.fork("fixed"), manual = repair.fork("manual");
        op(fixed, "complete", Request.class, CycleReceipt.class, "root");
        end(fixed, CycleReceipt.class, "complete.out");
        manual.terminated = true;
        assertThat(manual.dispatches).isEmpty();
    }

    @Test void childrenAndSearchUseOriginalForkInput() {
        Scope s = new Scope("search", Task1Result.class);
        Scope a = s.fork("ten"), b = s.fork("eleven");
        op(a, "ten", Task1Result.class, TaskReceipt.class, "root");
        op(a, "childA", Task1Result.class, SubResult.class, "root");
        op(b, "eleven", Task1Result.class, TaskReceipt.class, "root");
        op(b, "childB", Task1Result.class, SubResult.class, "root");
        assertThat(s.join("fork", a, b).parts()).extracting(Fact::id).containsExactly("childA.out", "childB.out");
        op(s, "search", Task1Result.class, SearchResults.class, "root");
        op(s, "rollup", SearchResults.class, Rollup.class, "search.out");
        end(s, Rollup.class, "rollup.out");
    }

    @Test void immutableCounterPatchesConvergeWithUnchangedBoard() {
        Scope s = new Scope("counters", CounterBoard.class);
        op(s, "one", CounterBoard.class, CounterBoard.class, "root");
        op(s, "two", CounterBoard.class, CounterBoard.class, "one.out");
        route(s, "flag", CounterBoard.class, "two.out");
        Scope yes = s.fork("yes"), no = s.fork("no");
        Scope a = yes.fork("three-four"), b = yes.fork("five"), c = yes.fork("ten");
        op(a, "three", CounterBoard.class, CounterPatch.class, "two.out");
        op(a, "four", PatchInput.class, CounterPatch.class, "PatchInput(two.out,three.out)");
        op(b, "five", CounterBoard.class, CounterPatch.class, "two.out");
        op(c, "ten", CounterBoard.class, CounterPatch.class, "two.out");
        assertThat(yes.join("patches", a, b, c).parts()).extracting(Fact::id).containsExactly("four.out", "five.out", "ten.out");
        op(yes, "apply", BoardUpdate.class, CounterBoard.class, "BoardUpdate(two.out,patches.aggregate)");
        s.merge("extra", yes, no);
        assertThat(s.captures).filteredOn(capture -> capture.fact().type() == CounterBoard.class)
                .singleElement().satisfies(capture -> assertThat(capture.arms()).extracting(Fact::id).containsExactly("apply.out", "two.out"));
        op(s, "six", CounterBoard.class, CounterBoard.class, "extra.capture<CounterBoard>");
        route(s, "final", CounterBoard.class, "six.out");
        for (String arm : List.of("seven", "eight")) {
            Scope p = s.fork(arm);
            op(p, arm, CounterBoard.class, CounterBoard.class, "six.out");
            end(p, CounterBoard.class, arm + ".out");
        }
    }

    @Test void shippingAssemblesDistinctSettledBranchRoles() {
        Scope s = new Scope("shipping", ReadyOrder.class);
        Scope billing = s.fork("billing"), fulfillment = s.fork("fulfillment");
        op(billing, "bill", ReadyOrder.class, Invoice.class, "root");
        op(billing, "payment", PaymentInput.class, PaymentReceipt.class, "PaymentInput(root,bill.out)");
        op(fulfillment, "stock", ReadyOrder.class, StockResult.class, "root");
        route(fulfillment, "production", StockResult.class, "stock.out");
        Scope make = fulfillment.fork("make"), stocked = fulfillment.fork("stocked");
        op(make, "plan", StockResult.class, ProductionPlan.class, "stock.out");
        op(make, "produce", ProductionPlan.class, Goods.class, "plan.out");
        op(stocked, "useStock", StockResult.class, Goods.class, "stock.out");
        fulfillment.merge("goods", make, stocked);
        assertThat(fulfillment.captures).filteredOn(capture -> capture.fact().type() == Goods.class)
                .singleElement().satisfies(capture -> assertThat(capture.arms()).extracting(Fact::id).containsExactly("produce.out", "useStock.out"));
        op(fulfillment, "fill", Goods.class, FilledOrder.class, "goods.capture<Goods>");
        s.join("order", billing, fulfillment);
        op(s, "ship", ShippingInput.class, Shipment.class, "ShippingInput(payment.out,fill.out)");
        end(s, Shipment.class, "ship.out");
    }

    @Test void exportAttemptPreservesItsOwnRootAndPipelineInput() {
        Scope child = Scope.child("attempt", ExportRequest.class);
        op(child, "start", ExportRequest.class, PipelineRun.class, "root");
        route(child, "namespace", PipelineRun.class, "start.out");
        Scope disabled = child.fork("disabled"), enabled = child.fork("enabled");
        op(disabled, "disabled", PipelineRun.class, ExportAttempt.class, "start.out");
        end(disabled, ExportAttempt.class, "disabled.out");
        op(enabled, "poll", PipelineRun.class, PipelineStatus.class, "start.out");
        op(enabled, "process", ProcessInput.class, ExportAttempt.class, "ProcessInput(root,start.out)");
        end(enabled, ExportAttempt.class, "process.out");
    }

    @Test void boundedExportRetainsTokenAndFinalAttemptAcrossCleanup() {
        Scope s = new Scope("export", ExportRequest.class);
        op(s, "reserve", ExportRequest.class, Token.class, "root");
        op(s, "acquire", Token.class, Lease.class, "reserve.out");
        for (int i = 1; i <= 3; i++) {
            Scope body = s.iteration("attempt", i);
            op(body, "attempt[" + i + "]", ExportRequest.class, ExportAttempt.class, "root");
            assertThat(body.control("stop[" + i + "]", ExportAttempt.class, Boolean.class).mapping()).isEqualTo("attempt[" + i + "].out");
            s.carry("export", body);
        }
        op(s, "return", Token.class, TokenReturn.class, "reserve.out");
        route(s, "exit", ExportAttempt.class, "export.result");
        for (String arm : List.of("disabled", "finished", "capped")) {
            Scope p = s.fork(arm);
            op(p, arm, ExportReplyInput.class, ExportReply.class, "ExportReplyInput(root,return.out,export.result)");
            end(p, ExportReply.class, arm + ".out");
        }
    }

    @Test void repeatedJobChildUsesRootRequestAndCurrentPoll() {
        Scope s = Scope.child("job", JobRequest.class);
        op(s, "submit", JobRequest.class, SubmitState.class, "root");
        route(s, "submitted", SubmitState.class, "submit.out");
        Scope notStarted = s.fork("notStarted"), started = s.fork("started");
        op(notStarted, "submission", SubmitState.class, JobResult.class, "submit.out");
        end(notStarted, JobResult.class, "submission.out");
        for (int i = 1; i <= 3; i++) {
            Scope iteration = started.iteration("poll", i);
            op(iteration, "poll[" + i + "]", JobRequest.class, PollState.class, "root");
            assertThat(iteration.control("finished", PollState.class, Boolean.class).mapping()).isEqualTo("poll[" + i + "].out");
            started.carry("poll", iteration);
        }
        op(started, "result", PollState.class, JobResult.class, "poll.result");
        end(started, JobResult.class, "result.out");
    }

    @Test void loadAndQueryKeepDistinctRequestsAndChildResults() {
        Scope s = new Scope("jobs", JobSettings.class);
        op(s, "loadCreation", JobSettings.class, LoadCreation.class, "root");
        route(s, "created", LoadCreation.class, "loadCreation.out");
        Scope noFiles = s.fork("noFiles"), created = s.fork("created");
        op(noFiles, "noFiles", JobSettings.class, Reply.class, "root");
        end(noFiles, Reply.class, "noFiles.out");
        op(created, "loadRequest", LoadInput.class, JobRequest.class, "LoadInput(loadCreation.out,root)");
        op(created, "loadChild", JobRequest.class, JobResult.class, "loadRequest.out");
        route(created, "loaded", JobResult.class, "loadChild.out");
        Scope failed = created.fork("failed"), loaded = created.fork("loaded");
        op(failed, "loadFailure", JobSettings.class, Reply.class, "root");
        end(failed, Reply.class, "loadFailure.out");
        op(loaded, "queryCreation", JobSettings.class, QueryCreation.class, "root");
        route(loaded, "queryCreated", QueryCreation.class, "queryCreation.out");
        Scope queryFailed = loaded.fork("queryFailed"), queryCreated = loaded.fork("queryCreated");
        op(queryFailed, "queryFailure", JobSettings.class, Reply.class, "root");
        end(queryFailed, Reply.class, "queryFailure.out");
        op(queryCreated, "queryRequest", QueryInput.class, JobRequest.class, "QueryInput(queryCreation.out,root)");
        op(queryCreated, "queryChild", JobRequest.class, JobResult.class, "queryRequest.out");
        route(queryCreated, "queried", JobResult.class, "queryChild.out");
        for (String arm : List.of("queryFailure", "done")) {
            Scope p = queryCreated.fork(arm);
            op(p, arm, JobSettings.class, Reply.class, "root");
            end(p, Reply.class, arm + ".out");
        }
    }

    @Test void scatterGatherSharesCompleteOrderedResultsWithTwoAggregators() {
        Scope s = new Scope("scatter", FastaFile.class);
        op(s, "split", FastaFile.class, list(Chunk.class), "root");
        Scope[] members = new Scope[40];
        for (int i = 0; i < members.length; i++) {
            members[i] = s.item("chunk[" + i + "]", Chunk.class);
            op(members[i], "blast[" + i + "]", Chunk.class, BlastResult.class, "chunk[" + i + "].item");
        }
        Fact aggregate = s.join("chunks", members);
        assertThat(aggregate.parts()).hasSize(40);
        for (int i = 0; i < 40; i++) assertThat(aggregate.parts().get(i).id()).isEqualTo("blast[" + i + "].out");
        Scope hits = s.fork("hits"), logs = s.fork("logs");
        op(hits, "hits", list(BlastResult.class), BlastFile.class, "chunks.aggregate");
        op(logs, "logs", list(BlastResult.class), LogFile.class, "chunks.aggregate");
        s.join("files", hits, logs);
        op(s, "assemble", BlastArtifacts.class, ArtifactBundle.class, "BlastArtifacts(hits.out,logs.out)");
        end(s, ArtifactBundle.class, "assemble.out");
    }

    @Test void packetBodiesUseItemThenPreparedPacketAndRetainManifestOrder() {
        Scope s = new Scope("packets", Request.class);
        op(s, "generate", Request.class, list(Packet.class), "root");
        Scope[] members = new Scope[3];
        for (int i = 0; i < members.length; i++) {
            members[i] = s.item("packet[" + i + "]", Packet.class);
            op(members[i], "prepare[" + i + "]", Packet.class, PreparedPacket.class, "packet[" + i + "].item");
            op(members[i], "send[" + i + "]", PreparedPacket.class, DispatchReceipt.class, "prepare[" + i + "].out");
        }
        assertThat(s.join("packets", members).parts()).extracting(Fact::id).containsExactly("send[0].out", "send[1].out", "send[2].out");
        op(s, "report", list(DispatchReceipt.class), DispatchReport.class, "packets.aggregate");
        end(s, DispatchReport.class, "report.out");
    }

    @Test void batchChildrenReceiveDistinctItemsAndCountReadsWholeAggregate() {
        Scope s = new Scope("batch", Request.class);
        op(s, "load", Request.class, list(DataRecord.class), "root");
        Scope a = s.item("record[0]", DataRecord.class), b = s.item("record[1]", DataRecord.class);
        op(a, "process[0]", DataRecord.class, ProcessedRecord.class, "record[0].item");
        op(b, "process[1]", DataRecord.class, ProcessedRecord.class, "record[1].item");
        assertThat(s.join("records", a, b).parts()).extracting(Fact::id).containsExactly("process[0].out", "process[1].out");
        op(s, "count", list(ProcessedRecord.class), Count.class, "records.aggregate");
        end(s, Count.class, "count.out");
    }

    @Test void automaticWashPassesEachCommittedResultForward() {
        Scope s = new Scope("wash", Laundry.class);
        op(s, "wash", Laundry.class, WashedLaundry.class, "root");
        op(s, "rinse", WashedLaundry.class, RinsedLaundry.class, "wash.out");
        op(s, "dry", RinsedLaundry.class, DryLaundry.class, "rinse.out");
        end(s, DryLaundry.class, "dry.out");
    }

    static void assess(Scope s, String name, Type subject, String expected) {
        assertThat(s.control(name, subject, Verdict.class).mapping()).isEqualTo(expected);
        assertThat(s.control(name + "Reading", Verdict.class, Interpretation.class).mapping()).isEqualTo(name + ".out");
    }

    @Test void assessedStoryRepliesReceiveSubjectRootAndSelectedEvidence() {
        Scope s = new Scope("judgedStory", StoryRequest.class);
        op(s, "outline", StoryRequest.class, Outline.class, "root");
        assess(s, "judge", Outline.class, "outline.out");
        Scope accepted = s.fork("accepted");
        op(accepted, "write", Outline.class, Reply.class, "outline.out");
        end(accepted, Reply.class, "write.out");
        for (String arm : List.of("rejected", "undecided", "inapplicable")) {
            Scope p = s.fork(arm);
            op(p, arm, StoryDecisionInput.class, Reply.class, "StoryDecisionInput(root,outline.out,judgeReading.out)");
            end(p, Reply.class, arm + ".out");
        }
        Scope unassessed = s.fork("unassessed");
        unassessed.terminated = true;
        assertThat(unassessed.dispatches).isEmpty();
    }

    @Test void revisionCarriesTheSelectedStateAcrossEveryIterationAndReading() {
        Scope s = new Scope("revision", StoryRequest.class);
        op(s, "start", StoryRequest.class, RevisionState.class, "root");
        for (int i = 1; i <= 5; i++) {
            Scope body = s.iteration("revision", i);
            String prefix = "r" + i;
            op(body, prefix + "Generate", RevisionState.class, GeneratedRevision.class, i == 1 ? "start.out" : "revision.result");
            assess(body, prefix + "Judge", GeneratedRevision.class, prefix + "Generate.out");
            List<String> readings = List.of("accepted", "rejected", "undecided", "inapplicable", "unassessed");
            Scope[] arms = new Scope[5];
            for (int a = 0; a < 5; a++) {
                String arm = prefix + readings.get(a);
                arms[a] = body.fork(arm);
                op(arms[a], arm, RevisionInput.class, RevisionState.class,
                        "RevisionInput(" + prefix + "Generate.out," + prefix + "Judge.out," + prefix + "JudgeReading.out)");
            }
            body.merge(prefix + "Review", arms);
            assertThat(body.captures).filteredOn(capture -> capture.fact().type() == RevisionState.class)
                    .singleElement().satisfies(capture -> assertThat(capture.arms()).extracting(Fact::id)
                            .containsExactly(prefix + "accepted.out", prefix + "rejected.out", prefix + "undecided.out", prefix + "inapplicable.out", prefix + "unassessed.out"));
            assertThat(body.control(prefix + "Complete", RevisionState.class, Boolean.class).mapping()).isEqualTo(prefix + "Review.capture<RevisionState>");
            s.carry("revision", body);
        }
        route(s, "outcome", RevisionState.class, "revision.result");
        for (String arm : List.of("finalOutline", "noApplicable")) {
            Scope p = s.fork(arm);
            op(p, arm, RevisionState.class, Reply.class, "revision.result");
            end(p, Reply.class, arm + ".out");
        }
        Scope failed = s.fork("notAssessed");
        failed.terminated = true;
        assertThat(failed.dispatches).isEmpty();
    }

    @Test void repairedOrOriginalBuildSurvivesCleanupAndTypedAssessmentJoin() {
        Scope s = new Scope("review", PrRequest.class);
        op(s, "fetch", PrRequest.class, PrContext.class, "root");
        op(s, "rebase", PrContext.class, RebaseResult.class, "fetch.out");
        op(s, "conflicts", RebaseResult.class, ConflictReport.class, "rebase.out");
        op(s, "test", ConflictReport.class, BuildResult.class, "conflicts.out");
        route(s, "repair", RepairInput.class, "RepairInput(rebase.out,conflicts.out,test.out)");
        Scope repair = s.fork("repair"), skip = s.fork("skip");
        op(repair, "fix", FixInput.class, FixResult.class, "FixInput(test.out,conflicts.out)");
        op(repair, "retest", ConflictReport.class, BuildResult.class, "conflicts.out");
        s.merge("build", repair, skip);
        assertThat(s.captures).filteredOn(capture -> capture.fact().type() == BuildResult.class)
                .singleElement().satisfies(capture -> assertThat(capture.arms()).extracting(Fact::id).containsExactly("retest.out", "test.out"));
        op(s, "cleanup", PrContext.class, CleanupReceipt.class, "fetch.out");
        assess(s, "judge", BuildResult.class, "build.capture<BuildResult>");
        Scope accepted = s.fork("accepted");
        Scope quality = accepted.fork("quality"), backport = accepted.fork("backport");
        op(quality, "quality", PrContext.class, QualityAssessment.class, "fetch.out");
        op(backport, "backport", PrContext.class, BackportAssessment.class, "fetch.out");
        accepted.join("assess", quality, backport);
        op(accepted, "full", FullReportInput.class, ReviewReport.class,
                "FullReportInput(quality.out,backport.out,fetch.out,judge.out,judgeReading.out)");
        end(accepted, ReviewReport.class, "full.out");
        for (String arm : List.of("early", "unresolved", "inapplicable")) {
            Scope p = s.fork(arm);
            op(p, arm, EarlyReportInput.class, ReviewReport.class, "EarlyReportInput(fetch.out,judgeReading.out,build.capture<BuildResult>)");
            end(p, ReviewReport.class, arm + ".out");
        }
        Scope failed = s.fork("notAssessed");
        failed.terminated = true;
        assertThat(failed.dispatches).isEmpty();
    }

    @Test void finalInputContainsTheExactEarlierAssembledInput() {
        Scope s = new Scope("savedInput", Request.class);
        op(s, "first", Request.class, FirstResult.class, "root");
        op(s, "second", SecondInput.class, SecondResult.class, "SecondInput(first.out,root)");
        op(s, "third", SecondResult.class, ThirdResult.class, "second.out");
        op(s, "fourth", ThirdResult.class, FourthResult.class, "third.out");
        op(s, "fifth", FifthInput.class, FinalResult.class, "FifthInput(fourth.out,second.in)");
        assertThat(s.dispatches.get("fifth").input().parts().get(1)).isSameAs(s.dispatches.get("second").input());
        end(s, FinalResult.class, "fifth.out");
    }
}
