package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import io.github.markpollack.workflow.flows.workflow.*;
import static org.assertj.core.api.Assertions.*;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

class CompilerBoundaryTest {
    public record State(String value) {}
    public record Missing(String value) {}
    enum Route { YES, NO }
    static final Duration DEADLINE=Duration.ofMinutes(2);
    static End success() { return new End(Terminal.SUCCEEDED,""); }
    static Call call(String label) { return new Call(label,Op.named(label,State.class,State.class)); }
    static Definition<?,?> definition(String name,Node... nodes) { return definition(name,List.of(nodes)); }
    static Definition<?,?> definition(String name,List<Node> nodes) { return new Definition<>(name,State.class,State.class,nodes,DEADLINE); }
    static class StateStep implements io.github.markpollack.workflow.flows.Step<State,State> {
        public State execute(io.github.markpollack.workflow.flows.StepContext c,State in) { return in; }
    }
    static class WrongStep implements io.github.markpollack.workflow.flows.Step<Missing,State> {
        public State execute(io.github.markpollack.workflow.flows.StepContext c,Missing in) { return new State(in.value()); }
    }
    static class FirstStep implements io.github.markpollack.workflow.flows.Step<State,First> {
        public First execute(io.github.markpollack.workflow.flows.StepContext c,State in) { return new First(in.value()); }
    }
    static class SecondStep implements io.github.markpollack.workflow.flows.Step<SecondInput,Second> {
        public Second execute(io.github.markpollack.workflow.flows.StepContext c,SecondInput in) { return new Second(in.original().value()); }
    }
    static class ThirdStep implements io.github.markpollack.workflow.flows.Step<Second,Third> {
        public Third execute(io.github.markpollack.workflow.flows.StepContext c,Second in) { return new Third(in.value()); }
    }
    static class FourthStep implements io.github.markpollack.workflow.flows.Step<Third,Fourth> {
        public Fourth execute(io.github.markpollack.workflow.flows.StepContext c,Third in) { return new Fourth(in.value()); }
    }
    static class FifthStep implements io.github.markpollack.workflow.flows.Step<FifthInput,Final> {
        public Final execute(io.github.markpollack.workflow.flows.StepContext c,FifthInput in) { return new Final(in.latest().value()); }
    }
    static Map<Placement,io.github.markpollack.workflow.flows.Step<?,?>> pins(Definition<?,?> d) {
        Map<Placement,io.github.markpollack.workflow.flows.Step<?,?>> pins=new LinkedHashMap<>();
        List<io.github.markpollack.workflow.flows.Step<?,?>> steps=List.of(new StateStep(),new FirstStep(),new SecondStep(),new ThirdStep(),new FourthStep(),new FifthStep());
        StructuredWorkflowCompiler.compile(d).bindings().forEach(binding -> pins.put(binding.placement(),steps.stream()
            .filter(step -> { var types=StepTypes.of(step.getClass()); return types.input().equals(binding.input().type()) && types.output().equals(binding.output().type()); })
            .findFirst().orElseThrow()));
        return pins;
    }

    @Test void ownershipPrecedesAnalysisAndThereIsOnlyOneCallerListRead() {
        Call first=call("operation");
        Call second=new Call("operation",Op.declared("operation",Missing.class,State.class));
        int[] reads={0};
        List<Node> changing=new AbstractList<>() {
            public int size() { return 2; }
            public Node get(int index) { return index==0?(++reads[0]==1?first:second):success(); }
        };
        Compilation<?,?> compiled=StructuredWorkflowCompiler.compile(definition("owned",changing));
        assertThat(reads[0]).isEqualTo(1);
        assertThat(((Call)compiled.definition().nodes().getFirst()).operation().input()).isEqualTo(State.class);
        assertThat(compiled.bindings().getFirst().input().type()).isEqualTo(State.class);
        assertThat(StructuredWorkflowCompiler.compile(compiled.definition()).bindings()).isEqualTo(compiled.bindings());
    }

    @Test void ownershipIncludesNestedCollectionsAndReflectiveTypes() {
        Type[] arguments={State.class};
        ParameterizedType callerType=new ParameterizedType() {
            public Type getRawType() { return List.class; }
            public Type getOwnerType() { return null; }
            public Type[] getActualTypeArguments() { return arguments; }
        };
        List<Node> nodes=new ArrayList<>(List.of(new Call("pass",Op.declared("pass",callerType,callerType)),success()));
        Definition<?,?> d=new Definition<>("types",callerType,callerType,nodes,DEADLINE);
        Compilation<?,?> compiled=StructuredWorkflowCompiler.compile(d);
        arguments[0]=Missing.class; nodes.clear();
        assertThat(((ParameterizedType)compiled.input()).getActualTypeArguments()).containsExactly(State.class);
        assertThat(compiled.definition().nodes()).hasSize(2);
        assertThatThrownBy(()->compiled.definition().nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(StructuredWorkflowCompiler.compile(compiled.definition()).bindings()).isEqualTo(compiled.bindings());
    }

    @Test void recursiveContainmentAndRecursiveTypeRefuseBeforeAnalysis() {
        List<Node> body=new ArrayList<>(); body.add(new Loop("loop",Op.named("done",State.class,Boolean.class),1,LimitPolicy.FAIL,body));
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(definition("cycle",body))).hasMessageContaining("cyclic");
        ParameterizedType cycle=new ParameterizedType() {
            public Type getRawType() { return List.class; }
            public Type getOwnerType() { return null; }
            public Type[] getActualTypeArguments() { return new Type[]{this}; }
        };
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(new Definition<>("typeCycle",cycle,cycle,List.of(success()),DEADLINE))).hasMessageContaining("cyclic");
    }

    @Test void malformedContainmentAndGenericArityRefuse() {
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(definition("null",Arrays.asList((Node)null)))).isInstanceOf(IllegalArgumentException.class);
        ParameterizedType malformed=new ParameterizedType() {
            public Type getRawType() { return List.class; } public Type getOwnerType() { return null; }
            public Type[] getActualTypeArguments() { return new Type[]{State.class,Missing.class}; }
        };
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(new Definition<>("arity",malformed,malformed,List.of(success()),DEADLINE))).hasMessageContaining("arity");
    }

    @Test void nonreturningChildCannotFeedAnyNormalResultBoundary() {
        Definition<?,?> child=definition("failed",new End(Terminal.FAILED,"failed"));
        Child call=new Child("child",child);
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(definition("tail",call,success()))).hasMessageContaining("unreachable");
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(definition("loop",new Loop("repeat",Op.named("test",State.class,Boolean.class),2,LimitPolicy.EXIT,List.of(call)),success()))).hasMessageContaining("normal result required");
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(definition("group",new Parallel("group",null,true,List.of(new Member("member",List.of(call)))),success()))).hasMessageContaining("normal result required");
        Definition<?,?> fan=new Definition<>("fan",new ListType(State.class),new ListType(State.class),List.of(new Fan("items",State.class,2,1,true,List.of(call)),success()),DEADLINE);
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(fan)).hasMessageContaining("normal result required");
    }

    @Test void childNonSuccessMapsToFailedInvocationThroughDecisions() {
        Choice choice=new Choice("choose",Op.named("choose",State.class,Route.class),null,List.of(
                new Arm(Route.YES,List.of(new Child("failed",definition("failed",new End(Terminal.FAILED,"reason"))))),
                new Arm(Route.NO,List.of(new Child("cancelled",definition("cancelled",new End(Terminal.CANCELLED,"cancel")))))));
        Compilation<?,?> compiled=StructuredWorkflowCompiler.compile(definition("terminal-choice",choice));
        RegionSummary summary=compiled.summaries().get(new SummaryKey(Coordinates.root(compiled.definition()),"root"));
        assertThat(summary.continues()).isFalse(); assertThat(summary.terminals()).containsExactly(Terminal.FAILED);
        assertThat(summary.normalExits()).isEmpty();
        assertThat(GraphVerification.reachable(compiled.graph(),compiled.graph().startNode())).hasSize(compiled.graph().nodes().size());
        assertThatThrownBy(()->StructuredWorkflowCompiler.compile(definition("bad-tail",choice,success()))).hasMessageContaining("unreachable");
    }

    @Test void mixedChoiceExportsOnlyItsContinuingArm() {
        Choice choice=new Choice("choose",Op.named("choose",State.class,Route.class),null,List.of(
                new Arm(Route.YES,List.of(new End(Terminal.FAILED,"stop"))),new Arm(Route.NO,List.of(call("continue")))));
        Compilation<?,?> compiled=StructuredWorkflowCompiler.compile(definition("mixed",choice,success()));
        RegionSummary summary=compiled.summaries().get(new SummaryKey(Coordinates.node(Coordinates.root(compiled.definition()),choice,0),"root"));
        assertThat(summary.continues()).isTrue(); assertThat(summary.terminals()).containsExactly(Terminal.FAILED);
        assertThat(summary.result().display()).isEqualTo("continue.out");
        for(Placement exit:summary.normalExits()) assertThat(GraphVerification.reachable(compiled.graph(),summary.placement().graphName())).contains(exit.graphName());
    }

    @Test void sequentialAdmissionPinsBindingsAndHasNoMutableCollections() {
        Definition<?,?> d=definition("sequential",call("first"),call("second"),success());
        Map<Placement,io.github.markpollack.workflow.flows.Step<?,?>> selected=new LinkedHashMap<>(pins(d));
        ValidatedWorkflow admitted=ValidatedWorkflow.compile(d,selected); selected.clear();
        assertThat(admitted.graph().bindings()).hasSize(2);
        assertThat(admitted.graph().bindings().getLast().input()).isEqualTo(admitted.graph().bindings().getFirst().output());
        assertThat(admitted.terminal().successValue()).isEqualTo(admitted.graph().bindings().getLast().output().identity());
        assertThat(admitted.capabilities()).containsExactlyInAnyOrder(Capability.OPERATION,Capability.TERMINAL);
        assertThat(admitted.authoredIdentity()).startsWith("sha256:");
        assertThatThrownBy(()->admitted.values().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->admitted.graph().nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->admitted.graph().bindings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void failedTerminalHasNoFabricatedSuccessValue() {
        var d=definition("failed",call("work"),new End(Terminal.FAILED,"explicit reason"));
        ValidatedWorkflow workflow=ValidatedWorkflow.compile(d,pins(d));
        assertThat(workflow.terminal().intent()).isEqualTo(Terminal.FAILED);
        assertThat(workflow.terminal().successValue()).isNull(); assertThat(workflow.terminal().reason()).isEqualTo("explicit reason");
    }

    @Test void everyUnsupportedConstructRefusesProductionAdmission() {
        List<Node> nodes=List.of(
                new Parallel("parallel",null,true,List.of()),
                new Fan("fan",State.class,1,1,true,List.of(call("body"))),
                new Loop("loop",Op.named("test",State.class,Boolean.class),1,LimitPolicy.FAIL,List.of(call("body"))),
                new WorkflowModel.Timer("timer",Duration.ZERO));
        for(Node node:nodes) assertThatThrownBy(()->ValidatedWorkflow.compile(definition("unsupported",node,success()),Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported production capability");
    }

    @Test void unresolvedCompositeRefusesAtSelectionBoundary() {
        assertThatThrownBy(() -> ValidatedWorkflow.compile(definition("unresolved",
                new Child("child", definition("child", call("work"), success())), success()), Map.of()))
                .hasMessageContaining("missing validated child selection");
    }

    @Test void missingExtraAndWrongExecutableSelectionsRefuse() {
        Definition<?,?> d=definition("pins",call("one"),success());
        Map<Placement,io.github.markpollack.workflow.flows.Step<?,?>> selections=pins(d); Placement placement=selections.keySet().iterator().next();
        assertThatThrownBy(()->ValidatedWorkflow.compile(d,Map.of())).hasMessageContaining("missing executable");
        Map<Placement,io.github.markpollack.workflow.flows.Step<?,?>> extra=new HashMap<>(selections); extra.put(placement.child("other","x",0),new StateStep());
        assertThatThrownBy(()->ValidatedWorkflow.compile(d,extra)).hasMessageContaining("extraneous");
        assertThatThrownBy(()->ValidatedWorkflow.compile(d,Map.of(placement,new WrongStep()))).hasMessageContaining("contract disagreement");
    }

    @Test void malformedGraphFailsIndependentLoweringVerification() {
        Compilation<?,?> c=StructuredWorkflowCompiler.compile(definition("graph",call("one"),success()));
        List<WorkflowEdge> edges=new ArrayList<>(c.graph().edges()); edges.add(WorkflowEdge.sequence(c.graph().startNode(),"missing"));
        WorkflowGraph<?,?> invalid=new WorkflowGraph<>("graph",c.graph().nodes(),edges,c.graph().startNode(),c.graph().finishNode(),c.bindings());
        assertThatThrownBy(()->GraphVerification.verify(invalid,c.metadata(),c.summaries(),c.bindings(),c.captures(),c.products(),c.phaseMetadata(),Set.of(),c.definition())).hasMessageContaining("region topology");
        edges=new ArrayList<>(c.graph().edges()); edges.add(WorkflowEdge.sequence(c.graph().finishNode(),c.graph().startNode()));
        WorkflowGraph<?,?> cycle=new WorkflowGraph<>("graph",c.graph().nodes(),edges,c.graph().startNode(),c.graph().finishNode(),c.bindings());
        assertThatThrownBy(()->GraphVerification.verify(cycle,c.metadata(),c.summaries(),c.bindings(),c.captures(),c.products(),c.phaseMetadata(),Set.of(),c.definition())).hasMessageContaining("region topology");
    }

    public record Bounded<T extends Number>(T value) {}
    public record First(String value) {}
    public record SecondInput(First first,State original) {}
    public record Second(String value) {}
    public record Third(String value) {}
    public record Fourth(String value) {}
    public record FifthInput(Fourth latest,SecondInput earlier) {}
    public record Final(String value) {}
    static ParameterizedType parameterized(Class<?> raw,Type... args) {
        return new ParameterizedType() {
            public Type getRawType() { return raw; }
            public Type getOwnerType() { return raw.getDeclaringClass(); }
            public Type[] getActualTypeArguments() { return args; }
        };
    }
    @Test void malformedGenericContractsCannotEnterAnyBoundary() {
        for(Type invalid:List.of(parameterized(Bounded.class,String.class),parameterized(List.class,int.class),parameterized(String.class))) {
            Definition<?,?> d=new Definition<>("generic",invalid,invalid,List.of(success()),DEADLINE);
            assertThatThrownBy(()->StructuredWorkflowCompiler.compile(d)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->ValidatedWorkflow.compile(d,Map.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->new TypeContracts().requireType(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        Type valid=parameterized(Bounded.class,Integer.class);
        assertThat(new TypeContracts().contract(valid).javaType()).contains("Integer");
        assertThatThrownBy(() -> ValidatedWorkflow.compile(definition("empty",success()),Map.of()))
            .hasMessageContaining("at least one Step");
    }

    @Test void admittedRecipesRetainExactEarlierAssembledInput() {
        Definition<?,?> d=new Definition<>("saved-input",State.class,Final.class,List.of(
                new Call("first",Op.named("first",State.class,First.class)),
                new Call("second",Op.named("second",SecondInput.class,Second.class)),
                new Call("third",Op.named("third",Second.class,Third.class)),
                new Call("fourth",Op.named("fourth",Third.class,Fourth.class)),
                new Call("fifth",Op.named("fifth",FifthInput.class,Final.class)),success()),DEADLINE);
        ValidatedWorkflow admitted=ValidatedWorkflow.compile(d,pins(d));
        ValueId secondInput=admitted.graph().bindings().get(1).input().identity();
        ValidatedWorkflow.ValueRecipe fifthInput=admitted.values().get(admitted.graph().bindings().get(4).input().identity());
        assertThat(fifthInput.components()).containsExactly(admitted.graph().bindings().get(3).output().identity(),secondInput);
        assertThat(admitted.values().get(secondInput).components()).containsExactly(admitted.graph().bindings().getFirst().output().identity(),admitted.graph().bindings().getFirst().input().identity());
    }

    @Test void explicitCancellationRemainsDifferentFromChildInvocationFailure() {
        var d=definition("cancel",call("work"),new End(Terminal.CANCELLED,"owner"));
        ValidatedWorkflow admitted=ValidatedWorkflow.compile(d,pins(d));
        assertThat(admitted.rootSummary().terminals()).containsExactly(Terminal.CANCELLED);
        assertThat(admitted.terminal().successValue()).isNull();
    }

    @Test void graphBypassAndCounterfeitContinuationSummaryRefuse() {
        Compilation<?,?> c=StructuredWorkflowCompiler.compile(definition("graph",call("first"),call("second"),success()));
        Placement first=c.bindings().getFirst().placement();
        String terminal=c.metadata().entrySet().stream().filter(e->e.getValue().kind().equals("terminal")).map(e->e.getKey().graphName()).findFirst().orElseThrow();
        List<WorkflowEdge> edges=new ArrayList<>(c.graph().edges()); edges.add(WorkflowEdge.sequence(first.graphName(),terminal));
        WorkflowGraph<?,?> bypass=new WorkflowGraph<>("graph",c.graph().nodes(),edges,c.graph().startNode(),c.graph().finishNode(),c.bindings());
        assertThatThrownBy(()->GraphVerification.verify(bypass,c.metadata(),c.summaries(),c.bindings(),c.captures(),c.products(),c.phaseMetadata(),Set.of(),c.definition())).hasMessageContaining("region topology");
        Map<SummaryKey,RegionSummary> summaries=new HashMap<>(c.summaries());
        summaries.put(new SummaryKey(first,"root"),new RegionSummary(first,"root",false,null,List.of(),Set.of(Terminal.FAILED),List.of(),List.of(),Set.of(Capability.OPERATION),Map.of()));
        assertThatThrownBy(()->GraphVerification.verify(c.graph(),c.metadata(),summaries,c.bindings(),c.captures(),c.products(),c.phaseMetadata(),Set.of(),c.definition())).hasMessageContaining("continuation summary");
    }

    @Test void childCancellationFailsTheAllSuccessfulParentWithoutChangingChildEvidence() {
        Choice routes=new Choice("route",Op.named("route",State.class,Route.class),null,List.of(
                new Arm(Route.YES,List.of(success())),new Arm(Route.NO,List.of(new End(Terminal.CANCELLED,"child cancelled")))));
        Definition<?,?> child=definition("child",routes);
        Compilation<?,?> own=StructuredWorkflowCompiler.compile(child);
        assertThat(own.summaries().get(new SummaryKey(Coordinates.root(own.definition()),"root")).terminals()).containsExactlyInAnyOrder(Terminal.SUCCEEDED,Terminal.CANCELLED);
        Definition<?,?> parent=new Definition<>("parent",State.class,new ListType(State.class),List.of(
                new Parallel("group",null,true,List.of(new Member("one",List.of(new Child("child-call",child))))),success()),DEADLINE);
        Compilation<?,?> compiled=StructuredWorkflowCompiler.compile(parent);
        assertThat(compiled.summaries().get(new SummaryKey(Coordinates.root(compiled.definition()),"root")).terminals()).containsExactlyInAnyOrder(Terminal.SUCCEEDED,Terminal.FAILED);
    }

    @Test void changingGraphNodeKindCannotPreserveVerification() {
        Compilation<?,?> c=StructuredWorkflowCompiler.compile(definition("kinds",call("first"),success()));
        List<WorkflowNode> nodes=new ArrayList<>(c.graph().nodes());
        nodes.set(0,new WorkflowNode.ForkNode(nodes.getFirst().name(),"missing-join"));
        WorkflowGraph<?,?> malformed=new WorkflowGraph<>("kinds",nodes,c.graph().edges(),c.graph().startNode(),c.graph().finishNode(),c.bindings());
        assertThatThrownBy(()->GraphVerification.verify(malformed,c.metadata(),c.summaries(),c.bindings(),c.captures(),c.products(),c.phaseMetadata(),Set.of(),c.definition())).hasMessageContaining("node kind");
    }
}
