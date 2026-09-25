package io.github.markpollack.workflow.batch.durable;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;
import io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

public final class KernelFixtures {
    public record Request(String text) {}
    public record First(String text) {}
    public record SecondInput(First first,Request original) {}
    public record Second(String text) {}
    public record Third(String text) {}
    public record Fourth(First laterFirst,Request laterRequest,String text) {}
    public record FifthInput(Fourth latest,SecondInput earlier) {}
    public record ChangedFifthInput(First latest,SecondInput earlier) {}
    public record Reply(String text) {}
    public record Nested<T>(T item,List<T> items) {}

    public static class FirstOperation implements DurableOperation<Request,First> {
        public First execute(Request input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"first",context,input.text());return new First("first:"+input.text());
        }
    }
    public static class SecondOperation implements DurableOperation<SecondInput,Second> {
        public Second execute(SecondInput input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"second",context,input.first().text()+"/"+input.original().text());
            return new Second("second:"+input.first().text()+"/"+input.original().text());
        }
    }
    public static class ThirdOperation implements DurableOperation<Second,Third> {
        public Third execute(Second input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"third",context,input.text());return new Third(input.text());
        }
    }
    public static class FourthOperation implements DurableOperation<Third,Fourth> {
        public Fourth execute(Third input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"fourth",context,input.text());return new Fourth(new First("changed-first"),new Request("changed-request"),input.text());
        }
    }
    public static class FifthOperation implements DurableOperation<FifthInput,Reply> {
        public Reply execute(FifthInput input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"fifth",context,input.earlier().first().text()+"/"+input.earlier().original().text());
            return new Reply(input.earlier().first().text()+"/"+input.earlier().original().text()+"/"+input.latest().laterFirst().text());
        }
    }
    public static class ChangedThird implements DurableOperation<Second,Request> {
        public Request execute(Second input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"third",context,input.text());return new Request("changed-request");
        }
    }
    public static class ChangedFourth implements DurableOperation<Request,First> {
        public First execute(Request input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"fourth",context,input.text());return new First("changed-first");
        }
    }
    public static class ChangedFifth implements DurableOperation<ChangedFifthInput,Reply> {
        public Reply execute(ChangedFifthInput input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"fifth",context,input.earlier().first().text()+"/"+input.earlier().original().text());
            return new Reply(input.earlier().first().text()+"/"+input.earlier().original().text()+"/"+input.latest().text());
        }
    }
    public static class Echo implements DurableOperation<Request,Request> {
        public Request execute(Request input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"echo",context,input.text());return new Request(input.text()+config.getOrDefault("suffix",""));
        }
    }
    public static class NullOutput implements DurableOperation<Request,Request> {
        public Request execute(Request input,DeliveryContext context,Map<String,String> config) { return null; }
    }
    public static class Slow implements DurableOperation<Request,Request> {
        public Request execute(Request input,DeliveryContext context,Map<String,String> config) throws Exception {
            Thread.sleep(2200);return input;
        }
    }
    public static class Throws implements DurableOperation<Request,Request> {
        public Request execute(Request input,DeliveryContext context,Map<String,String> config) { throw new IllegalStateException("business failure"); }
    }
    public static class NestedEcho implements DurableOperation<Nested<Request>,Nested<Request>> {
        public Nested<Request> execute(Nested<Request> input,DeliveryContext context,Map<String,String> config) { return input; }
    }
    public static class Blocking implements DurableOperation<Request,Request> {
        public Request execute(Request input,DeliveryContext context,Map<String,String> config) throws Exception {
            OperationEvidence.count(config,"blocking",context,input.text());
            Path dir=Path.of(config.get("evidence"));
            Files.writeString(dir.resolve("entered-"+context.generation()),Long.toString(ProcessHandle.current().pid()));
            while(!Files.exists(dir.resolve("release-"+context.generation()))) Thread.sleep(5);
            return new Request("generation:"+context.generation());
        }
    }
    static ApplicationDeployment deployment(Map<String,String> config) {
        return deployment("fixture-v1",config);
    }
    static ApplicationDeployment deployment(String build,Map<String,String> config) {
        return new ApplicationDeployment("kernel-fixtures",build,config,List.of(FirstOperation.class,SecondOperation.class,
                ThirdOperation.class,FourthOperation.class,FifthOperation.class,ChangedThird.class,ChangedFourth.class,
                ChangedFifth.class,Echo.class,NullOutput.class,Slow.class,Throws.class,NestedEcho.class,Blocking.class));
    }
    static ValidatedWorkflow echo(ApplicationDeployment deployment,Class<? extends DurableOperation<?,?>> handler,Duration duration) {
        return single(deployment,handler,Request.class,Request.class,duration,Terminal.SUCCEEDED);
    }
    static ValidatedWorkflow single(ApplicationDeployment deployment,Class<? extends DurableOperation<?,?>> handler,java.lang.reflect.Type in,
            java.lang.reflect.Type out,Duration duration,Terminal terminal) {
        Definition<?,?> definition=new Definition<>("simple",in,out,List.of(new Call("work",Op.declared("work",in,out)),new End(terminal,"authored reason")),duration);
        Placement placement=new Placement(List.of(new Segment("workflow","simple",0),new Segment("call","work",0)));
        // Derive the public compiler's stable placement; no second binding resolver.
        var compilation=StructuredWorkflowCompiler.compile(new Definition<>(definition.name(),in,out,definition.nodes(),duration==null?Duration.ofHours(1):duration));
        placement=compilation.bindings().getFirst().placement();
        return ValidatedWorkflow.compile(definition,Map.of(placement,deployment.selection(handler,in,out)));
    }
    static ValidatedWorkflow o01(ApplicationDeployment deployment) {
        return o01(deployment,false);
    }
    static ValidatedWorkflow o01(ApplicationDeployment deployment,boolean changedComponents) {
        List<Call> calls=List.of(new Call("first",Op.named("first",Request.class,First.class)),
                new Call("second",Op.named("second",SecondInput.class,Second.class)),
                new Call("third",Op.declared("third",Second.class,changedComponents?Request.class:Third.class)),
                new Call("fourth",Op.declared("fourth",changedComponents?Request.class:Third.class,changedComponents?First.class:Fourth.class)),
                new Call("fifth",Op.declared("fifth",changedComponents?ChangedFifthInput.class:FifthInput.class,Reply.class)));
        List<Node> nodes=new ArrayList<>(calls);nodes.add(new End(Terminal.SUCCEEDED,""));
        Definition<?,?> definition=new Definition<>("earlier-input",Request.class,Reply.class,nodes,null);
        var construction=StructuredWorkflowCompiler.compile(new Definition<>(definition.name(),definition.input(),definition.output(),nodes,Duration.ofHours(1)));
        List<Class<? extends DurableOperation<?,?>>> handlers=List.of(FirstOperation.class,SecondOperation.class,
                changedComponents?ChangedThird.class:ThirdOperation.class,changedComponents?ChangedFourth.class:FourthOperation.class,
                changedComponents?ChangedFifth.class:FifthOperation.class);
        Map<Placement,ExecutableIdentity> selected=new LinkedHashMap<>();
        for(int i=0;i<calls.size();i++) selected.put(construction.bindings().get(i).placement(),deployment.selection(handlers.get(i),calls.get(i).operation().input(),calls.get(i).operation().output()));
        return ValidatedWorkflow.compile(definition,selected);
    }
}
