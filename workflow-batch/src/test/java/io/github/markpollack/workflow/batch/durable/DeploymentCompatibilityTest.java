package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.tools.ToolProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.batch.durable.KernelFixtures.*;
import static io.github.markpollack.workflow.batch.durable.StoreTestSupport.*;

class DeploymentCompatibilityTest {
    @TempDir Path directory;
    @Test void missingHistoricalInputAndMalformedCommittedBytesNeverReconstructOrRerun() throws Exception {
        var deployment=deployment(Map.of());var workflow=o01(deployment,true);
        for(String change:List.of("missing","decode","provenance")) {
            Path file=directory.resolve(change);
            try(var runtime=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility())) {
                controlledClock(file,8_000_000);var admitted=runtime.start(workflow,"run",new Request("original"));
                String lease=admitted.runId();
                for(int i=0;i<4;i++) runtime.advance(lease,workflow);
                var before=runtime.inspect(admitted.runId());String input=before.invocations().get(1).inputValue();
                mutate(file,admitted.runId(),state->{
                    ObjectNode values=(ObjectNode)state.path("values");ObjectNode saved=(ObjectNode)values.path(input);
                    if(change.equals("missing"))values.remove(input);
                    else if(change.equals("decode")) {byte[] bytes="{\"first\":false}".getBytes(StandardCharsets.UTF_8);saved.put("payload",bytes);saved.put("digest",Digests.of(bytes));}
                    else saved.putArray("components").add("wrong-historical-producer");
                });
                String corrupt = state(file,admitted.runId());
                assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(WorkflowRefusal.class);
                assertThat(state(file,admitted.runId())).isEqualTo(corrupt);
                evidence(file,admitted.runId(),"saved-input-"+change);
            }
        }
    }

    @Test void changedBehaviorSelectionConfigurationBuildAndPolicyRefuseWithoutChangingRun() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of("suffix","a"));var workflow=echo(deployment,Echo.class,null);
        RunSnapshot admitted;
        try(var runtime=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility())) {
            admitted=runtime.start(workflow,"run",new Request("x"));
            for(var changed:List.of(echo(deployment,Echo.class,Duration.ofMinutes(5)),echo(deployment,Throws.class,null))) {
                assertThatThrownBy(()->runtime.resume(admitted.runId(),changed)).isInstanceOf(WorkflowRefusal.class);
                assertThat(runtime.inspect(admitted.runId())).isEqualTo(admitted);
            }
        }
        for(boolean terminal:List.of(false,true)) {
            if(terminal) try(var runtime=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility())) {runtime.resume(admitted.runId(),workflow);}
            String before=state(file,admitted.runId());
            for(var changed:List.of(deployment(Map.of("suffix","b")),deployment("fixture-v2",Map.of("suffix","a")))) {
                try(var other=DurableWorkflows.open(file, changed.registry(), changed.compatibility())) {
                    var selected=echo(changed,Echo.class,null);
                    assertThatThrownBy(()->other.advance(admitted.runId(),selected)).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->other.resume(admitted.runId(),selected)).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->other.result(admitted.runId(),selected)).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->other.start(selected,"run",new Request("x"))).isInstanceOf(WorkflowRefusal.class);
                    assertThat(state(file,admitted.runId())).isEqualTo(before);
                }
            }
            try(var other=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility(), new ExecutionPolicy(2))) {
                assertThatThrownBy(()->other.resume(admitted.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
                assertThat(state(file,admitted.runId())).isEqualTo(before);
            }
        }
    }

    @Test void missingRegistrationAndManagementOnlyHandleCannotExecute() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        RunSnapshot admitted;
        try(var runtime=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility())) {admitted=runtime.start(workflow,"run",new Request("x"));}
        var missing=new TestApplication("kernel-fixtures","fixture-v1",Map.of(),Map.of());
        try(var other=DurableWorkflows.open(file, missing.registry(), missing.compatibility())) {
            assertThatThrownBy(()->other.start(workflow,"new",new Request("x"))).isInstanceOf(WorkflowRefusal.class);
            assertThatThrownBy(()->other.resume(admitted.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
            assertThat(other.inspect(admitted.runId())).isEqualTo(admitted);
        }
        try(var management=DurableWorkflows.open(file)) {
            assertThatThrownBy(()->management.resume(admitted.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
            assertThat(management.discover()).hasSize(1);
            assertThat(management.cancel(admitted.runId(),"owner","stop").status()).isEqualTo(RunSnapshot.Status.CANCELLED);
        }
    }

    public static class Counting implements Step<Request,Request> {
        static int constructions, executions;
        private final String suffix;
        public Counting(String suffix) { this.suffix=suffix; constructions++; }
        public Request execute(StepContext context,Request input) {
            executions++;
            return new Request(input.text()+suffix);
        }
    }
    @Test void constructorSettingsAndDeclaredCompatibilityShareOneSource() throws Exception {
        Counting.constructions=0;Counting.executions=0;
        var config=new HashMap<>(Map.of("suffix","original"));
        Map<String,Step<?,?>> operations=new HashMap<>(steps(new Counting(config.get("suffix"))));
        var deployment=new TestApplication("application","immutable-build",config,operations);
        config.put("suffix","changed");operations.clear();
        assertThat(deployment.manifest().configurationDigest()).isEqualTo(
                new TestApplication("application","immutable-build",Map.of("suffix","original"),Map.of()).manifest().configurationDigest());
        var workflow=echo(deployment,Counting.class,null);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility())) {
            var run=runtime.start(workflow,"run",new Request("x"));
            assertThat(Counting.constructions).isEqualTo(1);assertThat(Counting.executions).isZero();
            runtime.resume(run.runId(),workflow);
            assertThat(runtime.result(run.runId(),workflow)).isEqualTo(new Request("xoriginal"));
            assertThat(Counting.constructions).isEqualTo(1);assertThat(Counting.executions).isEqualTo(1);
            assertThat(run.deployment()).isEqualTo(deployment.manifest());
        }
    }

    @Test void duplicateMissingAndWrongConcreteTypeRegistrationsRefuse() throws Exception {
        assertThatThrownBy(()->new TestApplication("app","v1",Map.of(),Map.of("",new Echo(new OperationEvidence(null), "")))).isInstanceOf(IllegalArgumentException.class);
        var deployment=deployment(Map.of());
        assertThatThrownBy(()->deployment.step(Counting.class.getName())).isInstanceOf(WorkflowRefusal.class);
        assertThatThrownBy(() -> single(deployment,Echo.class,First.class,First.class,null,Terminal.SUCCEEDED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contract disagreement");
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility())) {
            assertThat(runtime.discover()).isEmpty();
        }
    }

    @Test void savedCodecRuntimeAndManifestMismatchesRefuseBeforeDelivery() throws Exception {
        var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        for(String change:List.of("applicationId","buildId","configurationDigest","jacksonCoreVersion","jacksonDatabindVersion","javaRuntimeVersion","javaVendor","javaVmName","codec")) {
            Path file=directory.resolve(change);
            try(var runtime=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility())) {
                var run=runtime.start(workflow,"run",new Request("x"));
                mutate(file,run.runId(),state->{
                    ObjectNode manifest=(ObjectNode)state.path("deployment");
                    if(change.equals("codec")) ((ObjectNode)manifest.path("codec")).put("configuration","changed");
                    else manifest.put(change,"changed");
                });
                String before=state(file,run.runId());
                assertThatThrownBy(()->runtime.resume(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
                assertThat(state(file,run.runId())).isEqualTo(before);
                assertThat(runtime.inspect(run.runId()).invocations()).isEmpty();
                evidence(file,run.runId(),"manifest-"+change);
            }
        }
    }

    public static class LinkageFailure implements Step<Request,Request> {
        public Request execute(StepContext context,Request input) { throw new NoSuchMethodError("deployment omitted method"); }
    }
    @Test void runtimeLinkageFailureIsExplicitAndCannotProduceSuccess() throws Exception {
        var deployment=new TestApplication("app","v1",Map.of(),steps(new LinkageFailure()));
        var workflow=echo(deployment,LinkageFailure.class,null);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility())) {
            var run=runtime.start(workflow,"run",new Request("x"));var after=runtime.resume(run.runId(),workflow);
            assertThat(after.status()).isEqualTo(RunSnapshot.Status.FAILED);
            assertThat(after.reason().code()).isEqualTo("STEP_FAILED");
            assertThat(after.reason().message()).contains("NoSuchMethodError");
            assertThat(after.currentNode()).isEqualTo(workflow.graph().startNode());assertThat(after.invocations().getFirst().attempts()).hasSize(1);
            assertThatThrownBy(()->runtime.result(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
        }
    }

    public record Upper(String text) { public Upper { text=text.toUpperCase(Locale.ROOT); } }
    public static class UpperEcho implements Step<Upper,Upper> {
        public Upper execute(StepContext context,Upper input) {return input;}
    }
    @Test void sameShapeConstructorTransformationsCannotChangeSavedInputOrResult() throws Exception {
        var deployment=new TestApplication("app","v1",Map.of(),steps(new UpperEcho()));
        var workflow=single(deployment,UpperEcho.class,Upper.class,Upper.class,null,Terminal.SUCCEEDED);
        for(boolean terminal:List.of(false,true)) {
            Path file=directory.resolve("transform-"+terminal);
            try(var runtime=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility())) {
                var run=runtime.start(workflow,"run",new Upper("UPPER"));
                if(terminal) runtime.resume(run.runId(),workflow);
                mutate(file,run.runId(),state->{
                    String id=terminal?state.path("output").asText():state.path("values").fieldNames().next();
                    ObjectNode saved=(ObjectNode)state.path("values").path(id);
                    byte[] bytes="{\"text\":\"lower\"}".getBytes(StandardCharsets.UTF_8);
                    saved.put("payload",bytes);saved.put("digest",Digests.of(bytes));
                });
                String before=state(file,run.runId());
                if(terminal) assertThatThrownBy(()->runtime.result(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("decode");
                else {
                    String lease=run.runId();before=state(file,run.runId());
                    assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("decode");
                }
                assertThat(state(file,run.runId())).isEqualTo(before);
            }
        }
    }

    @Test void legacySchemaRefusesWithoutSchemaOrRowMigration() throws Exception {
        for(String header:List.of("\"format\":1,","\"format\":2,","","\"format\":4,","\"format\":3.5,","\"format\":4294967299,")) {
            Path file=directory.resolve("format-"+Math.abs(header.hashCode()));String json="{"+header+"\"id\":\"old-run\"}";
            try(var c=connect(file);var statement=c.createStatement()) {
                statement.execute("CREATE TABLE aw_run (state CLOB NOT NULL)");
                try(var insert=c.prepareStatement("INSERT INTO aw_run VALUES(?)")) {insert.setString(1,json);insert.executeUpdate();}
            }
            assertThatThrownBy(()->DurableWorkflows.open(file)).isInstanceOfSatisfying(WorkflowRefusal.class,ex->assertThat(ex.code()).isEqualTo("STORE_FORMAT"));
            try(var c=connect(file);var statement=c.createStatement()) {
                try(var rows=statement.executeQuery("SELECT state FROM aw_run")) {rows.next();assertThat(rows.getString(1)).isEqualTo(json);}
                try(var rows=statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='PUBLIC'")) {
                    List<String> names=new ArrayList<>();while(rows.next())names.add(rows.getString(1));assertThat(names).containsExactly("AW_RUN");
                }
            }
        }
    }

    @Test void malformedRunHeadersInSupportedStoreRefuseWithoutChangingDatabaseBytes() throws Exception {
        int sequence=0;
        for(String header:List.of("old","previous","absent","unknown","fraction","overflow","string")) {
            Path file=directory.resolve("supported-format-"+(sequence++));
            var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);String id;
            try(var runtime=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility())) {id=runtime.start(workflow,"one",new Request("x")).runId();}
            mutate(file,id,state->{
                ObjectNode object=(ObjectNode)state;
                switch(header) {
                    case "old" -> object.put("format",3);
                    case "previous" -> object.put("format",6);
                    case "absent" -> object.remove("format");
                    case "unknown" -> object.put("format",8);
                    case "fraction" -> object.put("format",4.5);
                    case "overflow" -> object.put("format",4294967300L);
                    case "string" -> object.put("format","4");
                    default -> throw new AssertionError(header);
                }
            });
            byte[] before=Files.readAllBytes(Path.of(file+".mv.db"));
            for(int attempt=0;attempt<2;attempt++) {
                assertThatThrownBy(()->DurableWorkflows.open(file)).isInstanceOfSatisfying(WorkflowRefusal.class,
                        ex->assertThat(ex.code()).isEqualTo("STORE_FORMAT"));
                assertThat(Files.readAllBytes(Path.of(file+".mv.db"))).isEqualTo(before);
            }
        }
    }

    @Test void falseProducerRefusesBeforeEntryHistoricalConsumptionOrResultAccess() throws Exception {
        for(String stage:List.of("root","output","assembled","terminal")) {
            Path file=directory.resolve("producer-"+stage),effects=directory.resolve("producer-effects-"+stage);
            var deployment=deployment(Map.of("evidence",effects.toString()));
            var workflow=o01(deployment,true);
            try(var runtime=DurableWorkflows.open(file, deployment.registry(), deployment.compatibility())) {
                var run=runtime.start(workflow,"one",new Request("original"));
                int steps=switch(stage) {case "output" -> 1;case "assembled" -> 4;case "terminal" -> 5;default -> 0;};
                for(int i=0;i<steps;i++)runtime.advance(run.runId(),workflow);
                var saved=runtime.inspect(run.runId());
                String value=switch(stage) {
                    case "output" -> saved.invocations().getFirst().outputValue();
                    case "assembled" -> saved.invocations().get(1).inputValue();
                    case "terminal" -> saved.invocations().getLast().outputValue();
                    default -> saved.values().getFirst().valueId();
                };
                mutate(file,run.runId(),state->((ObjectNode)state.path("values").path(value)).put("producer","nonexistent-invocation"));
                String before=state(file,run.runId());
                if(stage.equals("terminal")) assertThatThrownBy(()->runtime.result(run.runId(),workflow))
                        .isInstanceOfSatisfying(WorkflowRefusal.class,ex->assertThat(ex.code()).isEqualTo("VALUE_CHANGED"));
                else assertThatThrownBy(()->runtime.resume(run.runId(),workflow))
                        .isInstanceOfSatisfying(WorkflowRefusal.class,ex->assertThat(ex.code()).isEqualTo("VALUE_CHANGED"));
                assertThat(state(file,run.runId())).isEqualTo(before);
                String next=switch(stage) {case "output" -> "second";case "assembled" -> "fifth";default -> "first";};
                if(!stage.equals("terminal"))assertThat(Files.exists(effects.resolve(next+".calls"))).isFalse();
            }
        }
    }

    @Test void freshDatabaseContainsNoArtifactStore() throws Exception {
        Path file=directory.resolve("runs");
        try(var runtime=DurableWorkflows.open(file);var c=connect(file);var s=c.createStatement();
                var rows=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='PUBLIC'")) {
            List<String> names=new ArrayList<>();while(rows.next())names.add(rows.getString(1));
            assertThat(names).containsExactlyInAnyOrder("AW_RUN","AW_TRANSITION_LOCK","AW_STORE_FORMAT");
        }
    }

    @Test @SuppressWarnings("unchecked")
    void sameNameTypesFromDifferentApplicationLoadersRefuse() throws Exception {
        Map<String,String> sources=Map.of("app/Request.java","package app; public record Request(String text) {}",
                "app/Operation.java","package app; import io.github.markpollack.workflow.flows.*; import java.util.Map; public class Operation implements Step<Request,Request> { public Request execute(StepContext context,Request input){return input;} }");
        Path classes=directory.resolve("classes");Files.createDirectories(classes);
        List<String> args=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        for(var entry:sources.entrySet()) {Path file=directory.resolve("src").resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());args.add(file.toString());}
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))).isZero();
        try(var first=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader());
                var second=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            Class<?> type=first.loadClass("app.Request");
            var operation=(Class<? extends Step<?,?>>)second.loadClass("app.Operation");
            var deployment=new TestApplication("app","v1",Map.of(),steps(operation.getConstructor().newInstance()));
            assertThatThrownBy(() -> single(deployment,operation,type,type,null,Terminal.SUCCEEDED))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contract disagreement");
            try(var runtime=DurableWorkflows.open(directory.resolve("runs"), deployment.registry(), deployment.compatibility())) {
                assertThat(runtime.discover()).isEmpty();
            }
        }
    }

    @Test void malformedUnicodeCannotAliasDifferentConfigurationContent() {
        String malformed=String.valueOf((char)0xD800);
        for(var config:List.of(Map.of("value",malformed),Map.of(malformed,"value")))
            assertThatThrownBy(()->deployment(config)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unicode");
        assertThatThrownBy(()->new TestApplication("app",malformed,Map.of(),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(deployment(Map.of("value","?")).manifest().configurationDigest())
                .isNotEqualTo(deployment(Map.of("value","\uD83D\uDE00")).manifest().configurationDigest());
        var first=new LinkedHashMap<String,String>();first.put("b","two");first.put("a","one");
        var second=new LinkedHashMap<String,String>();second.put("a","one");second.put("b","two");
        assertThat(deployment(first).manifest()).isEqualTo(deployment(second).manifest());
    }
}
