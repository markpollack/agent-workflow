package io.github.markpollack.workflow.batch.durable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
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

class RetainedCompatibilityTest {
    @TempDir Path directory;
    @Test void descriptorLookingStringsAreDataAndMissingMembersRefuse() throws Exception {
        String request="package app; public record Request(String text) {}";
        String operation="package app; import io.github.markpollack.workflow.batch.durable.*; import java.util.Map; public class Operation implements DurableOperation<Request,Request> { public Request execute(Request in,DeliveryContext ctx,Map<String,String> cfg){return new Request(Helper.value()+\"Label;\");} }";
        Path source=compile("original-members",Map.of("app/Request.java",request,"app/Operation.java",operation,
                "app/Helper.java","package app; public class Helper { public static String value(){return \"ok:\";} }"));
        try(URLClassLoader consumer=new URLClassLoader(new URL[]{source.toUri().toURL()},getClass().getClassLoader());var runtime=DurableWorkflows.open(directory.resolve("runs"))) {
            Class<?> type=consumer.loadClass("app.Request");Object input=type.getConstructor(String.class).newInstance("x");
            var complete=ExecutableBundle.capture(List.of(source),Map.of());var valid=dynamic(complete,type,type);
            var run=runtime.start(valid,"literal",input,complete);runtime.resume(run.runId(),valid);
            assertThat(runtime.result(run.runId(),valid).toString()).isEqualTo("Request[text=ok:Label;]");
            Path changed=compile("changed-helper",Map.of("app/Helper.java","package app; public class Helper { public static String replacement(){return \"changed\";} }"));
            Files.copy(changed.resolve("app/Helper.class"),source.resolve("app/Helper.class"),StandardCopyOption.REPLACE_EXISTING);
            var incomplete=ExecutableBundle.capture(List.of(source),Map.of());
            assertThatThrownBy(()->runtime.start(dynamic(incomplete,type,type),"missing-member",input,incomplete)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("member missing");
            assertThat(runtime.discover()).isEmpty();
        }
    }
    @Test void changedBehaviorExecutableConfigurationAndPolicyRefuseSameRun() throws Exception {
        Path file=directory.resolve("runs");var bundle=bundle(Map.of("suffix","a"));var workflow=echo(bundle,Echo.class,null);
        try(var runtime=DurableWorkflows.open(file)) {
            var admitted=runtime.start(workflow,"run",new Request("x"),bundle);
            var duration=echo(bundle,Echo.class,Duration.ofMinutes(5));
            var operation=echo(bundle,Throws.class,null);
            var newBundle=bundle(Map.of("suffix","b"));var config=echo(newBundle,Echo.class,null);
            for(var changed:List.of(duration,operation,config)) assertThatThrownBy(()->runtime.resume(admitted.runId(),changed)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("differs");
            assertThat(runtime.inspect(admitted.runId()).invocations()).isEmpty();
            var completed=runtime.resume(admitted.runId(),workflow);
            assertThatThrownBy(()->runtime.resume(admitted.runId(),operation)).isInstanceOf(WorkflowRefusal.class);
            assertThat(runtime.inspect(admitted.runId())).isEqualTo(completed);
        }
        try(var changedPolicy=DurableWorkflows.open(file,new ExecutionPolicy(Duration.ofSeconds(10),2))) {
            String run;try(var c=connect(file);var s=c.createStatement();var rows=s.executeQuery("SELECT id FROM aw_run")){rows.next();run=rows.getString(1);}
            assertThatThrownBy(()->changedPolicy.resume(run,workflow)).isInstanceOf(WorkflowRefusal.class);
        }
    }
    @Test void unavailableRetainedArtifactAndChangedCodecSelectionRefuseBeforeDelivery() throws Exception {
        var bundle=bundle(Map.of());var workflow=echo(bundle,Echo.class,null);
        for(String change:List.of("missing","bytes","codec")) {
            Path file=directory.resolve(change);
            try(var runtime=DurableWorkflows.open(file)) {
                var run=runtime.start(workflow,"run",new Request("x"),bundle);
                if(change.equals("codec")) mutate(file,run.runId(),node->((ObjectNode)node).put("codec","sha256:"+"0".repeat(64)));
                else try(var c=connect(file);var s=c.prepareStatement(change.equals("missing")?"DELETE FROM aw_artifact WHERE digest=?":"UPDATE aw_artifact SET payload=X'00' WHERE digest=?")) {
                    s.setString(1,bundle.closureDigest());s.executeUpdate();
                }
                assertThatThrownBy(()->runtime.resume(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class);
                assertThat(runtime.inspect(run.runId()).invocations()).isEmpty();evidence(file,run.runId(),"compatibility-"+change);
            }
        }
    }
    @Test void missingHistoricalInputAndMalformedCommittedBytesNeverReconstructOrRerun() throws Exception {
        var bundle=bundle(Map.of());var workflow=o01(bundle,true);
        for(String change:List.of("missing","decode","provenance")) {
            Path file=directory.resolve(change);
            try(var runtime=DurableWorkflows.open(file)) {
                controlledClock(file,8_000_000);var admitted=runtime.start(workflow,"run",new Request("original"),bundle);
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
    @Test void omittedStaticallyReferencedDependencyRefusesAdmission() throws Exception {
        Path source=compile("dependency",Map.of(
            "app/Request.java","package app; public record Request(String text) {}",
            "app/Helper.java","package app; public final class Helper { public static String value(){return \"helper\";} }",
            "app/Operation.java","package app; import io.github.markpollack.workflow.batch.durable.*; import java.util.Map; public class Operation implements DurableOperation<Request,Request> { public Request execute(Request in, DeliveryContext ctx, Map<String,String> cfg){return new Request(Helper.value());} }"));
        try(URLClassLoader consumer=new URLClassLoader(new URL[]{source.toUri().toURL()},getClass().getClassLoader())) {
            Class<?> request=consumer.loadClass("app.Request");Object input=request.getConstructor(String.class).newInstance("x");
            Path incomplete=directory.resolve("incomplete");Files.createDirectories(incomplete.resolve("app"));
            for(String name:List.of("Request","Operation"))Files.copy(source.resolve("app/"+name+".class"),incomplete.resolve("app/"+name+".class"));
            var bundle=ExecutableBundle.capture(List.of(incomplete),Map.of());var workflow=dynamic(bundle,request,request);
            try(var runtime=DurableWorkflows.open(directory.resolve("runs"))) {
                assertThatThrownBy(()->runtime.start(workflow,"missing",input,bundle)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("app.Helper");
                assertThat(runtime.discover()).isEmpty();
            }
        }
    }
    @Test void retainedAndConsumerRecordConstructorTransformationsRefuse() throws Exception {
        for(boolean transformInput:List.of(true,false)) {
            String request="package app; public record Request(String text) {%s}";
            String reply="package app; public record Reply(String text) {%s}";
            String operation="package app; import io.github.markpollack.workflow.batch.durable.*; import java.util.Map; public class Operation implements DurableOperation<Request,Reply> { public Reply execute(Request in,DeliveryContext ctx,Map<String,String> cfg){return new Reply(in.text());} }";
            String transformRequest="public Request { text=text.toUpperCase(java.util.Locale.ROOT); }";
            String transformReply="public Reply { text=text.toUpperCase(java.util.Locale.ROOT); }";
            Path consumerPath=compile("consumer-"+transformInput,Map.of("app/Request.java",request.formatted(""),"app/Reply.java",reply.formatted(transformInput?"":transformReply),"app/Operation.java",operation));
            Path retainedPath=compile("retained-"+transformInput,Map.of("app/Request.java",request.formatted(transformInput?transformRequest:""),"app/Reply.java",reply.formatted(""),"app/Operation.java",operation));
            try(URLClassLoader consumer=new URLClassLoader(new URL[]{consumerPath.toUri().toURL()},getClass().getClassLoader());var runtime=DurableWorkflows.open(directory.resolve("runs-"+transformInput))) {
                Class<?> inputType=consumer.loadClass("app.Request"),outputType=consumer.loadClass("app.Reply");
                var bundle=ExecutableBundle.capture(List.of(retainedPath),Map.of());var workflow=dynamic(bundle,inputType,outputType);
                Object input=inputType.getConstructor(String.class).newInstance("lower");
                if(transformInput) {
                    assertThatThrownBy(()->runtime.start(workflow,"run",input,bundle)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("decode");
                    assertThat(runtime.discover()).isEmpty();
                } else {
                    var run=runtime.start(workflow,"run",input,bundle);assertThat(runtime.resume(run.runId(),workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
                    assertThatThrownBy(()->runtime.result(run.runId(),workflow)).isInstanceOf(WorkflowRefusal.class).hasMessageContaining("decode");
                }
            }
        }
    }
    @Test void retainedBytesExecuteAfterOriginalApplicationFilesChangeOrDisappear() throws Exception {
        String request="package app; public record Request(String text) {}";
        String operation="package app; import io.github.markpollack.workflow.batch.durable.*; import java.util.Map; public class Operation implements DurableOperation<Request,Request> { public Request execute(Request in,DeliveryContext ctx,Map<String,String> cfg){return new Request(\"retained\");} }";
        Path source=compile("retention",Map.of("app/Request.java",request,"app/Operation.java",operation));
        try(URLClassLoader consumer=new URLClassLoader(new URL[]{source.toUri().toURL()},getClass().getClassLoader());var runtime=DurableWorkflows.open(directory.resolve("runs"))) {
            Class<?> type=consumer.loadClass("app.Request");var bundle=ExecutableBundle.capture(List.of(source),Map.of());var workflow=dynamic(bundle,type,type);
            var run=runtime.start(workflow,"run",type.getConstructor(String.class).newInstance("original"),bundle);
            Files.write(source.resolve("app/Operation.class"),new byte[]{0});
            assertThat(runtime.resume(run.runId(),workflow).status()).isEqualTo(RunSnapshot.Status.SUCCEEDED);
            assertThat(runtime.result(run.runId(),workflow).toString()).isEqualTo("Request[text=retained]");
            var changed=ExecutableBundle.capture(List.of(source),Map.of());assertThat(changed.closureDigest()).isNotEqualTo(bundle.closureDigest());
            assertThatThrownBy(()->runtime.resume(run.runId(),dynamic(changed,type,type))).isInstanceOf(WorkflowRefusal.class);
        }
    }
    private ValidatedWorkflow dynamic(ExecutableBundle bundle,Class<?> in,Class<?> out) {
        var definition=new Definition<>("dynamic",in,out,List.<Node>of(new Call("call",Op.declared("call",in,out)),new End(Terminal.SUCCEEDED,"")),Duration.ofMinutes(5));
        var placement=StructuredWorkflowCompiler.compile(definition).bindings().getFirst().placement();
        return ValidatedWorkflow.compile(definition,Map.of(placement,bundle.selection("app.Operation",in,out)));
    }
    private Path compile(String name,Map<String,String> sources) throws Exception {
        Path source=directory.resolve(name+"-src"),classes=directory.resolve(name+"-classes");Files.createDirectories(classes);
        List<String> options=new ArrayList<>(List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()));
        for(var entry:sources.entrySet()) {Path file=source.resolve(entry.getKey());Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());options.add(file.toString());}
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,options.toArray(String[]::new))).isZero();return classes;
    }
}
