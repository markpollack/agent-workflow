package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
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
            try(var runtime=DurableWorkflows.open(file,deployment)) {
                controlledClock(file,8_000_000);var admitted=runtime.start(workflow,"run",new Request("original"));
                var lease=runtime.claim(admitted.runId(),workflow,"one");
                for(int i=0;i<4;i++) runtime.advance(lease,workflow);
                var before=runtime.inspect(admitted.runId());String input=before.invocations().get(1).inputValue();
                mutate(file,admitted.runId(),state->{
                    ObjectNode values=(ObjectNode)state.path("values");ObjectNode saved=(ObjectNode)values.path(input);
                    if(change.equals("missing"))values.remove(input);
                    else if(change.equals("decode")) {byte[] bytes="{\"first\":false}".getBytes(StandardCharsets.UTF_8);saved.put("payload",bytes);saved.put("digest",Digests.of(bytes));}
                    else saved.putArray("components").add("wrong-historical-producer");
                });
                assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(WorkflowRefusal.class);
                var after=runtime.inspect(admitted.runId());assertThat(after.invocations()).isEqualTo(before.invocations());assertThat(after.nextOperation()).isEqualTo(4);
                assertThat(after.events()).isEqualTo(before.events());evidence(file,admitted.runId(),"saved-input-"+change);
            }
        }
    }

    @Test void changedBehaviorSelectionConfigurationBuildAndPolicyRefuseWithoutChangingRun() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of("suffix","a"));var workflow=echo(deployment,Echo.class,null);
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            var run=runtime.start(workflow,"run",new Request("x"));
            for(var changed:List.of(echo(deployment,Echo.class,Duration.ofMinutes(5)),echo(deployment,Throws.class,null))) {
                assertThatThrownBy(()->runtime.resume(run.runId(),changed)).isInstanceOf(WorkflowRefusal.class);
                assertThat(runtime.inspect(run.runId())).isEqualTo(run);
            }
            var lease=runtime.claim(run.runId(),workflow,"one");
            var claimed=runtime.inspect(run.runId());
            for(var changed:List.of(deployment(Map.of("suffix","b")),deployment("fixture-v2",Map.of("suffix","a")))) {
                try(var other=DurableWorkflows.open(file,changed)) {
                    var selected=echo(changed,Echo.class,null);
                    assertThatThrownBy(()->other.renew(lease)).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->other.claim(run.runId(),selected,"two")).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->other.advance(lease,selected)).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->other.resume(run.runId(),selected)).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->other.start(selected,"run",new Request("x"))).isInstanceOf(WorkflowRefusal.class);
                    assertThat(other.inspect(run.runId())).isEqualTo(claimed);
                }
            }
            try(var other=DurableWorkflows.open(file,deployment,new ExecutionPolicy(Duration.ofSeconds(10),2))) {
                assertThatThrownBy(()->other.renew(lease)).isInstanceOf(WorkflowRefusal.class);
                assertThat(other.inspect(run.runId())).isEqualTo(claimed);
            }
            runtime.advance(lease,workflow);var completed=runtime.inspect(run.runId());
            try(var other=DurableWorkflows.open(file,deployment("fixture-v2",Map.of("suffix","a")))) {
                assertThatThrownBy(()->other.resume(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
                assertThatThrownBy(()->other.result(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
                assertThatThrownBy(()->other.start(workflow,"run",new Request("x"))).isInstanceOf(WorkflowRefusal.class);
                assertThat(other.inspect(run.runId())).isEqualTo(completed);
            }
        }
    }

    @Test void missingRegistrationAndManagementOnlyHandleCannotExecuteOrRenew() throws Exception {
        Path file=directory.resolve("runs");var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        try(var runtime=DurableWorkflows.open(file,deployment)) {
            var run=runtime.start(workflow,"run",new Request("x"));var lease=runtime.claim(run.runId(),workflow,"one");
            var claimed=runtime.inspect(run.runId());
            var missing=new ApplicationDeployment("kernel-fixtures","fixture-v1",Map.of(),List.of());
            try(var other=DurableWorkflows.open(file,missing);var management=DurableWorkflows.open(file)) {
                for(var handle:List.of(other,management)) {
                    assertThatThrownBy(()->handle.start(workflow,"new",new Request("x"))).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->handle.resume(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
                    assertThatThrownBy(()->handle.renew(lease)).isInstanceOf(WorkflowRefusal.class);
                    assertThat(handle.inspect(run.runId())).isEqualTo(claimed);
                }
                assertThat(management.discover()).hasSize(1);
                assertThat(management.cancel(run.runId(),"owner","stop").status()).isEqualTo(RunSnapshot.Status.CANCELLED);
            }
        }
    }

    public static class Counting implements DurableOperation<Request,Request> {
        static int constructions, executions;
        public Counting() { constructions++; }
        public Request execute(Request input,DeliveryContext context,Map<String,String> config) {
            executions++;
            assertThatThrownBy(()->config.put("suffix","mutation")).isInstanceOf(UnsupportedOperationException.class);
            return new Request(input.text()+config.get("suffix"));
        }
    }
    @Test void fixedRegistrationAndConfigurationGovernActualApplicationInvocation() throws Exception {
        Counting.constructions=0;Counting.executions=0;
        var config=new HashMap<>(Map.of("suffix","original"));
        List<Class<? extends DurableOperation<?,?>>> operations=new ArrayList<>(List.of(Counting.class));
        var deployment=new ApplicationDeployment("application","immutable-build",config,operations);
        config.put("suffix","changed");operations.clear();
        assertThat(deployment.manifest().configurationDigest()).isEqualTo(
                new ApplicationDeployment("application","immutable-build",Map.of("suffix","original"),List.of()).manifest().configurationDigest());
        var workflow=echo(deployment,Counting.class,null);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            var run=runtime.start(workflow,"run",new Request("x"));
            assertThat(Counting.constructions).isZero();assertThat(Counting.executions).isZero();
            runtime.resume(run.runId(),workflow);
            assertThat(runtime.result(run.runId(),workflow)).isEqualTo(new Request("xoriginal"));
            assertThat(Counting.constructions).isEqualTo(1);assertThat(Counting.executions).isEqualTo(1);
            assertThat(run.deployment()).isEqualTo(deployment.manifest());
        }
    }

    @Test void duplicateMissingAndWrongConcreteTypeRegistrationsRefuse() throws Exception {
        assertThatThrownBy(()->new ApplicationDeployment("app","v1",Map.of(),List.of(Echo.class,Echo.class))).isInstanceOf(IllegalArgumentException.class);
        var deployment=deployment(Map.of());
        assertThatThrownBy(()->deployment.selection(Counting.class,Request.class,Request.class)).isInstanceOf(WorkflowRefusal.class);
        var wrong=single(deployment,Echo.class,First.class,First.class,null,Terminal.SUCCEEDED);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            assertThatThrownBy(()->runtime.start(wrong,"wrong",new First("x"))).isInstanceOf(WorkflowRefusal.class);
            assertThat(runtime.discover()).isEmpty();
        }
    }

    @Test void savedCodecRuntimeAndManifestMismatchesRefuseBeforeDelivery() throws Exception {
        var deployment=deployment(Map.of());var workflow=echo(deployment,Echo.class,null);
        for(String change:List.of("applicationId","buildId","configurationDigest","jacksonCoreVersion","jacksonDatabindVersion","javaRuntimeVersion","javaVendor","javaVmName","codec")) {
            Path file=directory.resolve(change);
            try(var runtime=DurableWorkflows.open(file,deployment)) {
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

    public static class LinkageFailure implements DurableOperation<Request,Request> {
        public Request execute(Request input,DeliveryContext context,Map<String,String> config) { throw new NoSuchMethodError("deployment omitted method"); }
    }
    @Test void runtimeLinkageFailureIsExplicitAndCannotProduceSuccess() throws Exception {
        var deployment=new ApplicationDeployment("app","v1",Map.of(),List.of(LinkageFailure.class));
        var workflow=echo(deployment,LinkageFailure.class,null);
        try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
            var run=runtime.start(workflow,"run",new Request("x"));var after=runtime.resume(run.runId(),workflow);
            assertThat(after.status()).isEqualTo(RunSnapshot.Status.FAILED);
            assertThat(after.reason().code()).isEqualTo("HANDLER_FAILED");
            assertThat(after.reason().message()).contains("NoSuchMethodError");
            assertThat(after.nextOperation()).isZero();assertThat(after.invocations().getFirst().deliveries()).hasSize(1);
            assertThatThrownBy(()->runtime.result(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
        }
    }

    public record Upper(String text) { public Upper { text=text.toUpperCase(Locale.ROOT); } }
    public static class UpperEcho implements DurableOperation<Upper,Upper> {
        public Upper execute(Upper input,DeliveryContext context,Map<String,String> config) {return input;}
    }
    @Test void sameShapeConstructorTransformationsCannotChangeSavedInputOrResult() throws Exception {
        var deployment=new ApplicationDeployment("app","v1",Map.of(),List.of(UpperEcho.class));
        var workflow=single(deployment,UpperEcho.class,Upper.class,Upper.class,null,Terminal.SUCCEEDED);
        for(boolean terminal:List.of(false,true)) {
            Path file=directory.resolve("transform-"+terminal);
            try(var runtime=DurableWorkflows.open(file,deployment)) {
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
                    var lease=runtime.claim(run.runId(),workflow,"one");before=state(file,run.runId());
                    assertThatThrownBy(()->runtime.advance(lease,workflow)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("decode");
                }
                assertThat(state(file,run.runId())).isEqualTo(before);
            }
        }
    }

    @Test void oldAbsentUnknownAndNonIntegerFormatsRefuseWithoutSchemaOrRowMigration() throws Exception {
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

    @Test void freshDatabaseContainsNoArtifactStore() throws Exception {
        Path file=directory.resolve("runs");
        try(var runtime=DurableWorkflows.open(file);var c=connect(file);var s=c.createStatement();
                var rows=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema='PUBLIC'")) {
            List<String> names=new ArrayList<>();while(rows.next())names.add(rows.getString(1));
            assertThat(names).containsExactlyInAnyOrder("AW_RUN","AW_TRANSITION_LOCK");
        }
    }

    @Test @SuppressWarnings("unchecked")
    void sameNameTypesFromDifferentApplicationLoadersRefuse() throws Exception {
        Map<String,String> sources=Map.of("app/Request.java","package app; public record Request(String text) {}",
                "app/Operation.java","package app; import io.github.markpollack.workflow.batch.durable.*; import java.util.Map; public class Operation implements DurableOperation<Request,Request> { public Request execute(Request input,DeliveryContext context,Map<String,String> config){return input;} }");
        Path classes=directory.resolve("classes");Files.createDirectories(classes);
        List<String> args=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        for(var entry:sources.entrySet()) {Path file=directory.resolve("src").resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());args.add(file.toString());}
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new))).isZero();
        try(var first=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader());
                var second=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            Class<?> type=first.loadClass("app.Request");
            var operation=(Class<? extends DurableOperation<?,?>>)second.loadClass("app.Operation");
            var deployment=new ApplicationDeployment("app","v1",Map.of(),List.of(operation));
            var workflow=single(deployment,operation,type,type,null,Terminal.SUCCEEDED);
            try(var runtime=DurableWorkflows.open(directory.resolve("runs"),deployment)) {
                Object input=type.getConstructor(String.class).newInstance("x");
                assertThatThrownBy(()->runtime.start(workflow,"run",input)).isInstanceOfSatisfying(WorkflowRefusal.class,ex->assertThat(ex.code()).isEqualTo("EXECUTABLE_CONTRACT"));
                assertThat(runtime.discover()).isEmpty();
            }
        }
    }

    @Test void malformedUnicodeCannotAliasDifferentConfigurationContent() {
        String malformed=String.valueOf((char)0xD800);
        for(var config:List.of(Map.of("value",malformed),Map.of(malformed,"value")))
            assertThatThrownBy(()->deployment(config)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unicode");
        assertThatThrownBy(()->new ApplicationDeployment("app",malformed,Map.of(),List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(deployment(Map.of("value","?")).manifest().configurationDigest())
                .isNotEqualTo(deployment(Map.of("value","\uD83D\uDE00")).manifest().configurationDigest());
        var first=new LinkedHashMap<String,String>();first.put("b","two");first.put("a","one");
        var second=new LinkedHashMap<String,String>();second.put("a","one");second.put("b","two");
        assertThat(deployment(first).manifest()).isEqualTo(deployment(second).manifest());
    }
}
