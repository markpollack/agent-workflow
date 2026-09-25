package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;

/** Structured Java construction contracts and immutable compiler facts. Not a portable execution format. */
public final class WorkflowModel {
    private WorkflowModel() {}
    public enum Terminal { SUCCEEDED, FAILED, CANCELLED }
    public enum LimitPolicy { FAIL, EXIT }
    public enum LoopExit { TEST_TRUE, CAP_REACHED, LIMIT_FAILURE, CONTINUE }

    public abstract static class TypeRef<T> {
        public final Type type() {
            Type parent=getClass().getGenericSuperclass();
            if(!(parent instanceof ParameterizedType p)||p.getRawType()!=TypeRef.class)
                throw new IllegalArgumentException("direct concrete TypeRef subclass required");
            return p.getActualTypeArguments()[0];
        }
    }
    public static final class Op<I,O> {
        private final String name;
        private final Type input;
        private final Type output;
        private Op(String name,Type input,Type output) { this.name=name;this.input=input;this.output=output; }
        public String name() { return name; }
        public Type input() { return input; }
        public Type output() { return output; }
        public static Op<?,?> declared(String name,Type input,Type output) { return new Op<>(name,input,output); }
        public static <I,O> Op<I,O> named(String name, Class<I> input, Class<O> output) { return new Op<>(name,input,output); }
        public static <I,O> Op<I,O> named(String name, TypeRef<I> input, TypeRef<O> output) { return new Op<>(name,input.type(),output.type()); }
        public static <I,O> Op<I,O> named(String name, Class<I> input, TypeRef<O> output) { return new Op<>(name,input,output.type()); }
        public static <I,O> Op<I,O> named(String name, TypeRef<I> input, Class<O> output) { return new Op<>(name,input.type(),output); }
    }
    public record Assessment<I>(String name, Type input) {}
    public record Definition<I,O>(String name, Type input, Type output, List<Node> nodes,Duration deadline) {
    }
    public sealed interface Node permits Call, Choice, Parallel, Fan, Loop, Child, Timer, End {}
    public record Call(String id, Op<?,?> operation) implements Node {}
    public record Choice(String id, Op<?,?> operation, Assessment<?> assessment, List<Arm> arms) implements Node {}
    public record Arm(Enum<?> outcome, List<Node> nodes) {}
    public record Parallel(String id, Type output, boolean allSuccessful, List<Member> members) implements Node {}
    public record Member(String name, List<Node> nodes) {}
    public record Fan(String id, Type element, int maxItems, int maxInFlight, boolean allSuccessful, List<Node> body) implements Node {}
    public record Loop(String id, Op<?,Boolean> test, int maxIterations, LimitPolicy policy, List<Node> body) implements Node {}
    public record Child(String id, Definition<?,?> definition) implements Node {}
    public record Timer(String id, Duration duration) implements Node {}
    public record End(Terminal terminal, String reason) implements Node {}

    /** Required compiler constructs; runtime support is admitted separately. */
    public enum Capability { OPERATION, TERMINAL, DECISION, VERDICT, PARALLEL, FAN, LOOP, CHILD, TIMER }
    /** Analysis templates are separate from runtime invocation coordinates. */
    public record SummaryKey(Placement placement, String phase) {}
    /** Authoritative authored effects and result of one node or lexical sequence. */
    public record RegionSummary(Placement placement, String phase, boolean continues, Fact result,
            List<Placement> normalExits, java.util.Set<Terminal> terminals,
            List<Capture> captures, List<Product> products, java.util.Set<Capability> capabilities,
            Map<String,String> bounds) {
        public RegionSummary {
            normalExits=List.copyOf(normalExits); terminals=java.util.Set.copyOf(terminals);
            captures=List.copyOf(captures); products=List.copyOf(products);
            capabilities=java.util.Set.copyOf(capabilities); bounds=Map.copyOf(bounds);
            if (!continues && !normalExits.isEmpty()) throw new IllegalArgumentException("terminal region has normal exits");
        }
        public Fact requireNormalResult(String context) {
            if (!continues || result==null) throw new IllegalArgumentException(context+": normal result required");
            return result;
        }
    }
    public record Segment(String kind, String label, int ordinal) {}
    public record Placement(List<Segment> segments) {
        public Placement { segments = List.copyOf(segments); }
        public Placement child(String kind,String label,int ordinal) {
            List<Segment> next=new ArrayList<>(segments); next.add(new Segment(kind,label,ordinal)); return new Placement(next);
        }
        public String graphName() {
            StringBuilder result=new StringBuilder();
            for(Segment s:segments) result.append(s.kind().length()).append(':').append(s.kind())
                    .append(s.label().length()).append(':').append(s.label()).append('#').append(s.ordinal()).append(';');
            return result.toString();
        }
    }
    public record ValueId(Placement placement,String role,String phase) {}
    public record Fact(ValueId identity,String display,Type type,List<Fact> components,List<Fact> consumed) {
        public Fact { components=List.copyOf(components); consumed=List.copyOf(consumed); }
    }
    public record Binding(Placement placement,String phase,String operation,Fact input,Fact output,String expression) {}
    public record Capture(Placement placement,Fact result,List<Fact> alternatives) {
        public Capture { alternatives=List.copyOf(alternatives); }
    }
    public record Product(Placement placement,Fact result,List<Fact> members) {
        public Product { members=List.copyOf(members); }
    }
    public record Metadata(String kind,Type input,Type output,Map<String,String> configuration) {
        public Metadata { configuration=Map.copyOf(configuration); }
    }
    public record LoopContract(Placement placement,int maximum,LimitPolicy policy,Type output) {
        public LoopExit evaluate(boolean test,int completedIterations) {
            if(completedIterations<1||completedIterations>maximum) throw new IllegalArgumentException("invalid iteration coordinate");
            if(test) return LoopExit.TEST_TRUE;
            if(completedIterations<maximum) return LoopExit.CONTINUE;
            return policy==LimitPolicy.EXIT?LoopExit.CAP_REACHED:LoopExit.LIMIT_FAILURE;
        }
    }
    public static final class Compilation<I,O> {
        private final Definition<I,O> definition;
        private final WorkflowGraph<I,O> graph;
        private final Map<Placement,Metadata> metadata;
        private final List<Binding> bindings;
        private final List<Capture> captures;
        private final List<Product> products;
        private final List<LoopContract> loops;
        private final Map<SummaryKey,RegionSummary> summaries;
        private final Map<SummaryKey,Metadata> phaseMetadata;
        Compilation(Definition<I,O> definition,WorkflowGraph<I,O> graph,Map<Placement,Metadata> metadata,
                List<Binding> bindings,List<Capture> captures,List<Product> products,List<LoopContract> loops, Map<SummaryKey,RegionSummary> summaries, Map<SummaryKey,Metadata> phaseMetadata) {
            this.definition=definition;this.graph=graph;this.metadata=Map.copyOf(metadata);
            this.bindings=List.copyOf(bindings);this.captures=List.copyOf(captures);
            this.products=List.copyOf(products);this.loops=List.copyOf(loops);this.summaries=Map.copyOf(summaries);this.phaseMetadata=Map.copyOf(phaseMetadata);
        }
        public Map<SummaryKey,Metadata> phaseMetadata() { return phaseMetadata; }
        public Map<SummaryKey,RegionSummary> summaries() { return summaries; }
        public Definition<I,O> definition() { return definition; }
        public WorkflowGraph<I,O> graph() { return graph; }
        public Map<Placement,Metadata> metadata() { return metadata; }
        public List<Binding> bindings() { return bindings; }
        public List<Capture> captures() { return captures; }
        public List<Product> products() { return products; }
        public List<LoopContract> loops() { return loops; }
        public Type input() { return definition.input(); }
        public Type output() { return definition.output(); }
    }
    public record ListType(Type element) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return new Type[]{element}; }
        @Override public Type getRawType() { return List.class; }
        @Override public Type getOwnerType() { return null; }
        @Override public String getTypeName() { return "java.util.List<"+element.getTypeName()+">"; }
        @Override public boolean equals(Object other) { return other instanceof ParameterizedType p&&p.getRawType()==List.class&&Arrays.equals(getActualTypeArguments(),p.getActualTypeArguments()); }
        @Override public int hashCode() { return Arrays.hashCode(getActualTypeArguments())^List.class.hashCode(); }
    }
    public record ProductType(List<Type> roles) implements Type {
        public ProductType { roles=List.copyOf(roles); }
        @Override public String getTypeName() { return "product"+roles; }
    }
}
