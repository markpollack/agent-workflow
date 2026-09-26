package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.Type;
import java.util.*;
import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;
import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/**
 * Immutable compiler-verified workflow for an explicit runtime capability set.
 * The compile entry points create it; unchecked graphs and construction-only
 * compilations are not admission inputs. This is not a running workflow or durable run state.
 * The runtime must additionally compare the supplied deployment registration and configuration,
 * concrete type contracts and codec before admitting or recovering a run. No handler executes here.
 */
public final class ValidatedWorkflow {
    public static final String COMPILER_CONTRACT="structured-workflow-compiler-v1";
    private static final Set<Capability> SUPPORTED=Set.of(Capability.OPERATION,Capability.TERMINAL);

    /** Exact selected logical value source; components refer to retained identities, never runtime type search. */
    public record ValueRecipe(ValueId identity, Type declaration, TypeContracts.Contract contract,List<ValueId> components,List<ValueId> consumed) {
        public ValueRecipe { components=List.copyOf(components); consumed=List.copyOf(consumed); }
    }
    /**
     * Input aliases retain the original ValueId; assembled dispatch inputs have their own retained ID.
     * Executable is absent for child placements, whose selection is in {@link #children()}.
     */
    public record InvocationRecipe(Placement placement,ValueId input,ValueId output,ExecutableIdentity executable) {}
    /** Explicit terminal intent and selected success value (absent for non-success). */
    public record TerminalRecipe(Placement placement,Terminal intent,String reason,ValueId successValue) {}

    private final Definition<?,?> definition;
    private final WorkflowGraph<?,?> graph;
    private final RegionSummary rootSummary;
    private final List<InvocationRecipe> invocations;
    private final Map<Placement,ValidatedWorkflow> children;
    private final Map<ValueId,ValueRecipe> values;
    private final TerminalRecipe terminal;
    private final TypeContracts.Contract input,output;
    private final String authoredIdentity;
    private final TypeContracts.CodecIdentity codec;
    private final DeadlinePolicy deadlinePolicy;
    private final java.time.Duration authoredDuration;
    private final String deadlineOrigin;

    private ValidatedWorkflow(Compilation<?,?> compiled,Map<Placement,ExecutableIdentity> selections,
            Map<Placement,ValidatedWorkflow> children, DeadlinePolicy policy, java.time.Duration authoredDuration) {
        this.children=Map.copyOf(children);
        this.deadlinePolicy=policy; this.authoredDuration=authoredDuration;
        this.deadlineOrigin=policy.origin(authoredDuration);
        definition=compiled.definition(); graph=compiled.graph();
        rootSummary=compiled.summaries().get(new SummaryKey(Coordinates.root(definition),"root"));
        TypeContracts contracts=new TypeContracts(); codec=contracts.identity();
        input=contracts.contract(definition.input()); output=contracts.contract(definition.output());
        List<InvocationRecipe> calls=new ArrayList<>(); Map<ValueId,ValueRecipe> recipes=new LinkedHashMap<>();
        Set<Placement> required=new HashSet<>();
        for(Binding binding:compiled.bindings()) {
            ValidatedWorkflow child=children.get(binding.placement());
            if(child==null) required.add(binding.placement());
            ExecutableIdentity selected=selections.get(binding.placement());
            if(child==null&&selected==null) throw new IllegalArgumentException("missing executable selection at "+binding.placement());
            if(child==null&&(!contracts.compatible(binding.input().type(),selected.input())||!contracts.compatible(binding.output().type(),selected.output())))
                throw new IllegalArgumentException("executable contract disagreement at "+binding.placement());
            recipe(binding.input(),recipes,contracts); recipe(binding.output(),recipes,contracts);
            calls.add(new InvocationRecipe(binding.placement(),binding.input().identity(),binding.output().identity(),selected));
        }
        if(!required.equals(selections.keySet())) throw new IllegalArgumentException("extraneous executable selection");
        int last=definition.nodes().size()-1;
        Node tail=definition.nodes().get(last);
        Placement endPlacement=Coordinates.node(Coordinates.root(definition),tail,last);
        Fact finalValue;
        if(calls.isEmpty()) finalValue=new Fact(new ValueId(Coordinates.root(definition),"root","root"),"root",definition.input(),List.of(),List.of());
        else finalValue=compiled.bindings().getLast().output();
        recipe(finalValue,recipes,contracts);
        terminal=tail instanceof End end?new TerminalRecipe(endPlacement,end.terminal(),end.reason()==null?"":end.reason(),end.terminal()==Terminal.SUCCEEDED?finalValue.identity():null):null;
        invocations=List.copyOf(calls); values=Collections.unmodifiableMap(new LinkedHashMap<>(recipes));
        StringBuilder authored=new StringBuilder();
        for(String field:List.of(COMPILER_CONTRACT,Coordinates.SCHEME,definition.name(),definition.deadline().toString())) IdentityEncoding.field(authored,field);
        for(String field:List.of(policy.profile(),policy.maximum().toString(),
                authoredDuration==null?"default":authoredDuration.toString(),deadlineOrigin)) IdentityEncoding.field(authored,field);
        contractIdentity(authored,input); contractIdentity(authored,output);
        for(InvocationRecipe call:calls) {
            IdentityEncoding.field(authored,call.placement().graphName());
            valueIdentity(authored,call.input()); valueIdentity(authored,call.output());
            if(children.containsKey(call.placement())) {
                IdentityEncoding.field(authored,"child");
                IdentityEncoding.field(authored,children.get(call.placement()).authoredIdentity());
            }
        }
        for(ValueRecipe value:recipes.values()) {
            valueIdentity(authored,value.identity()); contractIdentity(authored,value.contract());
            IdentityEncoding.field(authored,Integer.toString(value.components().size()));
            value.components().forEach(id->valueIdentity(authored,id));
            IdentityEncoding.field(authored,Integer.toString(value.consumed().size()));
            value.consumed().forEach(id->valueIdentity(authored,id));
        }
        if(terminal!=null) {
            IdentityEncoding.field(authored,terminal.placement().graphName());
            IdentityEncoding.field(authored,terminal.intent().name()); IdentityEncoding.field(authored,terminal.reason());
            if(terminal.successValue()!=null) valueIdentity(authored,terminal.successValue());
        } else IdentityEncoding.field(authored,"nonreturning-child");
        authoredIdentity=IdentityEncoding.digest(authored.toString());
    }

    /**
     * Compiles and checks one owned definition for the fixed initial capability set.
     * Selections are deployment attestations keyed by stable authored call placements.
     * Unsupported composition refuses; support can expand only with compiler/runtime proof.
     * Authored identity fields must contain well-formed Unicode; valid text is not normalized.
     */
    public static ValidatedWorkflow compile(Definition<?,?> source,Map<Placement,ExecutableIdentity> selections) {
        return compile(source,selections,DeadlinePolicy.DEFAULT);
    }

    /** Compiles with a finite deployment policy; a null authored duration selects its default. */
    public static ValidatedWorkflow compile(Definition<?,?> source,Map<Placement,ExecutableIdentity> selections,
            DeadlinePolicy policy) {
        Definition<?,?> owned=DefinitionOwnership.acquire(source);
        Objects.requireNonNull(policy,"deadline policy");
        java.time.Duration authored=owned.deadline();
        owned=new Definition<>(owned.name(),owned.input(),owned.output(),owned.nodes(),policy.resolve(authored));
        Map<Placement,ExecutableIdentity> selected=Map.copyOf(selections);
        for(Node node:owned.nodes()) if(!SUPPORTED.contains(Coordinates.capability(node)))
            throw new IllegalArgumentException("unsupported production capability: "+Coordinates.capability(node));
        Compilation<?,?> compiled=StructuredWorkflowCompiler.compileOwned(owned);
        return new ValidatedWorkflow(compiled,selected,Map.of(),policy,authored);
    }

    /**
     * Compile a sequential parent with local child calls. Child selections are keyed by the
     * parent's call placement, and must match the authored child definition exactly. Each child
     * must use the sequential capability set; nested composition refuses before analysis.
     * Ordinary selections exclude child placements. No application code executes at compilation.
     *
     * @param source parent definition
     * @param selections ordinary operation selections
     * @param children validated child selections
     * @param policy finite parent deadline policy
     * @return immutable executable parent
     * @throws IllegalArgumentException for missing, conflicting or unsupported selections
     */
    public static ValidatedWorkflow compileWithChildren(Definition<?,?> source,
            Map<Placement,ExecutableIdentity> selections,Map<Placement,ValidatedWorkflow> children,DeadlinePolicy policy) {
        Definition<?,?> owned=DefinitionOwnership.acquire(source);
        Objects.requireNonNull(policy,"deadline policy");
        Map<Placement,ValidatedWorkflow> selectedChildren=Map.copyOf(children);
        Set<Placement> required=new HashSet<>();
        List<Node> nodes=new ArrayList<>();
        for(int i=0;i<owned.nodes().size();i++) {
            Node node=owned.nodes().get(i);
            if(node instanceof Child child) {
                Placement placement=Coordinates.node(Coordinates.root(owned),node,i);
                required.add(placement);
                ValidatedWorkflow selected=selectedChildren.get(placement);
                if(selected==null) throw new IllegalArgumentException("missing validated child selection at "+placement);
                if(!selected.children.isEmpty()) throw new IllegalArgumentException("unsupported production capability: nested child");
                Map<Placement,ExecutableIdentity> executable=new LinkedHashMap<>();
                selected.invocations.forEach(call->executable.put(call.placement(),call.executable()));
                ValidatedWorkflow expected=compile(child.definition(),executable,selected.deadlinePolicy);
                if(!expected.authoredIdentity.equals(selected.authoredIdentity))
                    throw new IllegalArgumentException("child definition disagreement at "+placement);
                nodes.add(new Child(child.id(),expected.definition()));
            } else {
                if(!SUPPORTED.contains(Coordinates.capability(node)))
                    throw new IllegalArgumentException("unsupported production capability: "+Coordinates.capability(node));
                nodes.add(node);
            }
        }
        if(!required.equals(selectedChildren.keySet())) throw new IllegalArgumentException("extraneous validated child selection");
        java.time.Duration authored=owned.deadline();
        owned=new Definition<>(owned.name(),owned.input(),owned.output(),List.copyOf(nodes),policy.resolve(authored));
        return new ValidatedWorkflow(StructuredWorkflowCompiler.compileOwned(owned),Map.copyOf(selections),selectedChildren,policy,authored);
    }

    private static void recipe(Fact fact,Map<ValueId,ValueRecipe> values,TypeContracts contracts) {
        if(values.containsKey(fact.identity())) return;
        fact.components().forEach(f->recipe(f,values,contracts)); fact.consumed().forEach(f->recipe(f,values,contracts));
        values.put(fact.identity(),new ValueRecipe(fact.identity(),fact.type(),contracts.contract(fact.type()),
                fact.components().stream().map(Fact::identity).toList(),fact.consumed().stream().map(Fact::identity).toList()));
    }

    private static void valueIdentity(StringBuilder target,ValueId value) {
        IdentityEncoding.field(target,value.placement().graphName()); IdentityEncoding.field(target,value.role()); IdentityEncoding.field(target,value.phase());
    }
    private static void contractIdentity(StringBuilder target,TypeContracts.Contract contract) {
        IdentityEncoding.field(target,contract.javaType()); IdentityEncoding.field(target,contract.shapeDigest());
    }

    public Definition<?,?> definition() { return definition; }
    public WorkflowGraph<?,?> graph() { return graph; }
    public RegionSummary rootSummary() { return rootSummary; }
    public Set<Capability> capabilities() { return children.isEmpty()?SUPPORTED:Set.of(Capability.OPERATION,Capability.TERMINAL,Capability.CHILD); }
    public String coordinateScheme() { return Coordinates.SCHEME; }
    public String compilerContract() { return COMPILER_CONTRACT; }
    public String authoredIdentity() { return authoredIdentity; }
    public TypeContracts.CodecIdentity codecIdentity() { return codec; }
    public TypeContracts.Contract inputContract() { return input; }
    public TypeContracts.Contract outputContract() { return output; }
    public List<InvocationRecipe> invocations() { return invocations; }
    /** Validated children, isolated from the parent's value namespace. */
    public Map<Placement,ValidatedWorkflow> children() { return children; }
    public Map<ValueId,ValueRecipe> values() { return values; }
    /** Explicit root terminal; a statically nonreturning child has no normal terminal continuation. */
    public TerminalRecipe terminal() {
        if(terminal==null) throw new IllegalStateException("nonreturning child has no normal terminal continuation");
        return terminal;
    }
    public DeadlinePolicy deadlinePolicy() { return deadlinePolicy; }
    public Optional<java.time.Duration> authoredDuration() { return Optional.ofNullable(authoredDuration); }
    public String deadlineOrigin() { return deadlineOrigin; }
}
