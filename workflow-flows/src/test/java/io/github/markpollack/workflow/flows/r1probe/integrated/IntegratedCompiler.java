package io.github.markpollack.workflow.flows.r1probe.integrated;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.verdict.Verdict.Conclusion;
import io.github.markpollack.workflow.flows.r1probe.evidence.PinnedRecordCodec;
import io.github.markpollack.workflow.flows.workflow.EdgeCondition;
import io.github.markpollack.workflow.flows.workflow.WorkflowEdge;
import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;
import io.github.markpollack.workflow.flows.workflow.WorkflowNode;

import static io.github.markpollack.workflow.flows.r1probe.integrated.IntegratedModel.*;

/** One test-only complete-definition compiler. Its graph adapters deliberately refuse execution. */
public final class IntegratedCompiler {
    private final PinnedRecordCodec codec=new PinnedRecordCodec();
    private final List<Binding> bindings=new ArrayList<>();
    private final List<Capture> captures=new ArrayList<>();
    private final List<Product> products=new ArrayList<>();
    private final List<LoopContract> loops=new ArrayList<>();
    private final Map<Placement,Metadata> metadata=new LinkedHashMap<>();
    private final Set<Definition<?,?>> active;
    private Type boundaryOutput;
    private int successful;

    private IntegratedCompiler(Set<Definition<?,?>> active) { this.active=active; }

    public static Built<?,?> build(Definition<?,?> definition) {
        return new IntegratedCompiler(Collections.newSetFromMap(new IdentityHashMap<>())).compile(definition);
    }

    private <I,O> Built<I,O> compile(Definition<I,O> definition) {
        require(definition!=null,"unresolved definition");
        require(active.add(definition),"recursive child definition");
        try {
            require(definition.name()!=null&&!definition.name().isBlank(),"workflow name required");
            applicationType(definition.input()); applicationType(definition.output());
            finite(definition.deadline(),false,"finite positive workflow deadline required");
            boundaryOutput=definition.output();
            Placement root=new Placement(List.of(new Segment("workflow",definition.name(),0)));
            Scope scope=new Scope(root,definition.input());
            walk(definition.nodes(),scope,root,false);
            require(scope.terminated,"missing terminal or dangling path");
            Definition<I,O> frozen=new Definition<>(definition.name(),definition.input(),definition.output(),freeze(definition.nodes()),definition.deadline());
            Graph graph=new Graph(metadata);
            Fragment body=graph.sequence(frozen.nodes(),root);
            require(body.entry()!=null,"empty definition");
            String finish=root.child("completion","terminal",0).graphName();
            graph.addAdapter(finish,definition.output(),definition.output());
            for(String terminal:graph.terminals) graph.edges.add(WorkflowEdge.sequence(terminal,finish));
            WorkflowGraph<I,O> nativeGraph=WorkflowGraph.of(definition.name(),graph.nodes,graph.edges,body.entry(),finish);
            metadata.put(root.child("completion","terminal",0),new Metadata("completion",definition.output(),definition.output(),Map.of("deadline",definition.deadline().toString(),"execution","prototype-refuses")));
            return new Built<>(frozen,nativeGraph,metadata,bindings,captures,products,loops);
        } finally { active.remove(definition); }
    }

    private void applicationType(Type type) {
        require(type!=null,"concrete type required");
        require(!(type instanceof ProductType),"internal product is not an application type");
        codec.requireType(type);
    }

    private void internalProductType(Type type) {
        if(type instanceof ProductType product) product.roles().forEach(this::internalProductType);
        else applicationType(type);
    }

    private static void finite(Duration duration,boolean zero,String message) {
        require(duration!=null&&!duration.isNegative()&&(zero||!duration.isZero()),message);
        try { duration.toNanos(); } catch(ArithmeticException ex) { throw new IllegalArgumentException(message+": overflow",ex); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    private static String name(Node node) {
        return switch(node) {
            case Call n->n.id(); case Choice n->n.id(); case Parallel n->n.id(); case Fan n->n.id();
            case Loop n->n.id(); case Child n->n.id(); case Timer n->n.id(); case End n->"terminate";
        };
    }
    private void walk(List<Node> nodes,Scope scope,Placement parent,boolean member) {
        require(nodes!=null,"missing sequence");
        Set<String> labels=new HashSet<>();
        for(int index=0;index<nodes.size();index++) {
            Node node=nodes.get(index);
            require(node!=null,"null node"); require(!scope.terminated,"unreachable authored work");
            String label=name(node);
            require(label!=null&&!label.isBlank(),"placement name required");
            require(node instanceof End||labels.add(label),"duplicate authored placement "+label);
            Placement placement=parent.child("node",label,index);
            switch(node) {
                case Call call -> {
                    require(call.operation()!=null,"missing operation");
                    call(scope,placement,call.id(),call.operation().input(),call.operation().output(),false);
                }
                case Timer timer -> {
                    finite(timer.duration(),true,"finite nonnegative timer required");
                    meta(placement,"timer",typeOf(scope.carrier),typeOf(scope.carrier),Map.of("duration",timer.duration().toString()));
                }
                case Child child -> {
                    require(child.definition()!=null,"unresolved child");
                    Built<?,?> validated=new IntegratedCompiler(active).compile(child.definition());
                    call(scope,placement,child.id(),validated.input(),validated.output(),false);
                    boolean returns=validated.metadata().values().stream().anyMatch(m->m.kind().equals("terminal")&&"SUCCEEDED".equals(m.configuration().get("intent")));
                    scope.terminated=!returns;
                    meta(placement,"child",validated.input(),validated.output(),Map.of("definition",validated.definition().name(),"returns",Boolean.toString(returns)));
                }
                case End end -> {
                    require(!member,"root terminal inside member/body");
                    require(end.terminal()!=null,"terminal intent required");
                    if(end.terminal()==Terminal.SUCCEEDED) {
                        require(scope.carrier!=null&&scope.carrier.type().equals(boundaryOutput),
                                "successful output disagreement: expected "+boundaryOutput+", current "+(scope.carrier==null?"none":scope.carrier.type()));
                        applicationType(scope.carrier.type()); successful++;
                    } else require(end.reason()!=null&&!end.reason().isBlank(),"non-success terminal reason required");
                    meta(placement,"terminal",typeOf(scope.carrier),typeOf(scope.carrier),Map.of("intent",end.terminal().name(),"value",scope.carrier==null?"none":scope.carrier.display()));
                    scope.terminated=true;
                }
                case Choice choice -> {
                    require((choice.operation()==null)!=(choice.assessment()==null),"exactly one decision/assessment contract required");
                    Class<?> domain;
                    if(choice.operation()!=null) {
                        require(choice.operation().output() instanceof Class<?> c&&c.isEnum(),"concrete enum decision required");
                        domain=(Class<?>)choice.operation().output();
                        call(scope,placement,choice.id(),choice.operation().input(),domain,true);
                    } else {
                        domain=Conclusion.class;
                        call(scope,placement,choice.id(),choice.assessment().input(),Verdict.class,true);
                        call(scope,placement.child("evidence","interpretation",0),choice.id()+"Reading",Verdict.class,Conclusion.class,true);
                    }
                    require(choice.arms()!=null,"choice arms required");
                    Set<Enum<?>> expected=new LinkedHashSet<>();
                    for(Object value:domain.getEnumConstants()) expected.add((Enum<?>)value);
                    Set<Enum<?>> seen=new LinkedHashSet<>();
                    List<Scope> arms=new ArrayList<>();
                    for(int a=0;a<choice.arms().size();a++) {
                        Arm arm=choice.arms().get(a);
                        require(arm!=null&&arm.outcome()!=null&&expected.contains(arm.outcome()),"foreign/null choice outcome");
                        require(seen.add(arm.outcome()),"duplicate choice outcome "+arm.outcome());
                        Placement path=placement.child("arm",arm.outcome().name(),a);
                        Scope local=scope.fork(path);
                        walk(arm.nodes(),local,path,member); arms.add(local);
                    }
                    require(seen.equals(expected),"missing choice outcome: expected "+expected+", received "+seen);
                    scope.merge(placement,choice.id(),arms);
                    if(!scope.terminated) meta(placement.child("join","choice",0),"exclusive-join",null,typeOf(scope.carrier),Map.of());
                    meta(placement,choice.assessment()==null?"decision":"verdict",choice.operation()!=null?choice.operation().input():choice.assessment().input(),choice.assessment()==null?domain:Verdict.class,
                            choice.assessment()==null?Map.of():Map.of("support","SUPPORTED","reading","non-null"));
                    if(choice.assessment()!=null) meta(placement.child("routing","native-reading",0),"native-route",Conclusion.class,Conclusion.class,Map.of("support","SUPPORTED","reading","non-null"));
                }
                case Parallel parallel -> {
                    if(parallel.output()!=null) applicationType(parallel.output());
                    require(parallel.allSuccessful(),"allSuccessful required");
                    require(parallel.members()!=null&&!parallel.members().isEmpty(),"nonempty static group required");
                    Set<String> members=new HashSet<>(); List<Fact> results=new ArrayList<>();
                    for(int m=0;m<parallel.members().size();m++) {
                        Member branch=parallel.members().get(m);
                        require(branch!=null&&branch.name()!=null&&members.add(branch.name()),"duplicate/null branch");
                        require(branch.nodes()!=null&&!branch.nodes().isEmpty(),"empty branch");
                        Placement path=placement.child("member",branch.name(),m);
                        Scope local=scope.fork(path); walk(branch.nodes(),local,path,true);
                        require(!local.terminated,"member must settle to join"); results.add(local.carrier);
                    }
                    scope.join(placement,parallel.id(),results,parallel.output());
                    meta(placement,"parallel",null,scope.carrier.type(),Map.of("policy","allSuccessful","order","declaration"));
                    meta(placement.child("join","parallel",0),"typed-join",null,scope.carrier.type(),Map.of("policy","allSuccessful"));
                }
                case Fan fan -> {
                    require(fan.maxItems()>0&&fan.maxInFlight()>0,"positive fan bounds required");
                    require(fan.allSuccessful(),"allSuccessful required");
                    require(fan.body()!=null&&!fan.body().isEmpty(),"one nonempty fan body required");
                    applicationType(fan.element());
                    Fact feeder=scope.resolve(new ListType(fan.element()),placement,fan.id()+".manifest",false);
                    Placement path=placement.child("body","item",0);
                    Scope local=scope.fork(path);
                    Fact item=fact(path,"item",scope.phase,fan.id()+".item",fan.element(),List.of(),List.of(feeder));
                    local.facts.add(item); local.carrier=item;
                    walk(fan.body(),local,path,true);
                    require(local.carrier!=null,"fan body result is not definitely assigned");
                    scope.join(placement,fan.id(),List.of(local.carrier),new ListType(local.carrier.type()));
                    meta(placement,"forEach",feeder.type(),scope.carrier.type(),Map.of("maxItems",""+fan.maxItems(),"maxInFlight",""+fan.maxInFlight(),"membership","immutable-ordered"));
                    meta(placement.child("join","fan",0),"typed-join",null,scope.carrier.type(),Map.of("order","manifest"));
                }
                case Loop loop -> {
                    require(loop.maxIterations()>0,"positive loop bound required");
                    require(loop.policy()!=null,"loop cap policy required");
                    require(loop.test()!=null&&Boolean.class.equals(loop.test().output()),"named Boolean loop test required");
                    require(loop.body()!=null&&!loop.body().isEmpty(),"one nonempty loop body required");
                    Placement path=placement.child("body","loop",0);
                    Scope first=scope.fork(path); first.phase=scope.phase+"/first";
                    walk(loop.body(),first,path,true);
                    Fact firstResult=first.carrier;
                    require(firstResult!=null,"loop result is not definitely assigned");
                    call(first,placement.child("test",loop.test().name(),0),loop.test().name(),loop.test().input(),Boolean.class,true);
                    Scope carried=scope.fork(path); carried.phase=scope.phase+"/carried";
                    carried.carry(placement,loop.id(),firstResult);
                    walk(loop.body(),carried,path,true);
                    require(carried.carrier!=null&&carried.carrier.type().equals(firstResult.type()),"loop carried output type changes");
                    call(carried,placement.child("test",loop.test().name(),0),loop.test().name(),loop.test().input(),Boolean.class,true);
                    scope.carry(placement,loop.id(),carried.carrier);
                    loops.add(new LoopContract(placement,loop.maxIterations(),loop.policy(),scope.carrier.type()));
                    meta(placement,"repeatUntil",null,scope.carrier.type(),Map.of("maximum",""+loop.maxIterations(),"cap",loop.policy().name(),"test",loop.test().name()));
                    meta(placement.child("exit","loop",0),"loop-exit",null,scope.carrier.type(),Map.of("cap",loop.policy().name()));
                }
            }
        }
    }

    private void meta(Placement placement,String kind,Type input,Type output,Map<String,String> config) { metadata.put(placement,new Metadata(kind,input,output,config)); }
    private void call(Scope scope,Placement placement,String name,Type input,Type output,boolean control) {
        applicationType(input); applicationType(output);
        Fact prior=scope.carrier;
        Fact value=scope.resolve(input,placement,name+".in",true);
        String expression=value.identity().placement().equals(placement)&&value.identity().role().equals("input")&&!value.components().isEmpty()
                ?shortName(input)+"("+value.components().stream().map(Fact::display).collect(Collectors.joining(","))+")":value.display();
        if(!scope.facts.contains(value)) scope.facts.add(value);
        Fact result=fact(placement,"output",scope.phase,name+".out",output,List.of(),List.of(value));
        if(!control) {
            if(value.type().equals(output)) scope.superseded.add(value);
            value.components().stream().filter(f->f.type().equals(output)).forEach(scope.superseded::add);
        }
        scope.facts.add(result); scope.missing.remove(output); scope.unproven.remove(output);
        scope.carrier=control?prior:result;
        bindings.add(new Binding(placement,scope.phase,name,value,result,expression));
        meta(placement,control?"control":"operation",input,output,Map.of());
    }
    private static Fact fact(Placement p,String role,String phase,String display,Type type,List<Fact> parts,List<Fact> consumed) {
        return new Fact(new ValueId(p,role,phase),display,type,parts,consumed);
    }
    private static String shortName(Type type) { return type instanceof Class<?> c?c.getSimpleName():type.getTypeName(); }
    private static Type typeOf(Fact fact) { return fact==null?null:fact.type(); }
    private static boolean consumes(Fact value,Fact earlier) {
        if(value.identity().equals(earlier.identity())) return true;
        return value.components().stream().anyMatch(f->consumes(f,earlier))||value.consumed().stream().anyMatch(f->consumes(f,earlier));
    }
    private final class Scope {
        final Placement path;
        final List<Fact> facts=new ArrayList<>();
        final Set<Fact> superseded=new HashSet<>();
        final Map<Type,String> missing=new LinkedHashMap<>();
        final Map<Type,List<Fact>> unproven=new LinkedHashMap<>();
        Fact carrier; String phase="root"; boolean terminated;
        Scope(Placement path,Type input) { this.path=path; carrier=fact(path,"root",phase,"root",input,List.of(),List.of()); facts.add(carrier); }
        Scope(Placement path,Scope outer) { this.path=path; facts.addAll(outer.facts); superseded.addAll(outer.superseded); missing.putAll(outer.missing); unproven.putAll(outer.unproven); carrier=outer.carrier; phase=outer.phase; }
        Scope fork(Placement path) { return new Scope(path,this); }
        List<Fact> candidates(Type type) { return facts.stream().filter(f->f.type().equals(type)&&!superseded.contains(f)).distinct().toList(); }
        Fact resolve(Type type,Placement placement,String label,boolean assemble) {
            applicationType(type);
            if(carrier!=null&&carrier.type().equals(type)) return carrier;
            if(carrier!=null&&carrier.identity().role().equals("product")
                    &&!(carrier.type() instanceof ParameterizedType p&&p.getRawType()==List.class)) {
                List<Fact> roles=carrier.components();
                List<Fact> direct=roles.stream().filter(f->f.type().equals(type)).toList();
                require(direct.size()<2,"ambiguous current product role "+type);
                if(direct.size()==1) return direct.getFirst();
                // A complete compatible current product precedes older whole values. Partial
                // record construction still follows ordinary whole-value lookup precedence.
                if(assemble) {
                    Class<?> record=rawRecord(type);
                    if(record!=null&&record.getRecordComponents().length==roles.size()) {
                        List<Fact> components=new ArrayList<>();
                        for(var component:record.getRecordComponents()) {
                            Type wanted=memberType(type,component.getGenericType());
                            List<Fact> matches=roles.stream().filter(f->f.type().equals(wanted)).toList();
                            if(matches.size()!=1) { components.clear(); break; }
                            components.add(matches.getFirst());
                        }
                        if(components.size()==roles.size()&&new HashSet<>(components).equals(new HashSet<>(roles)))
                            return fact(placement,"input",phase,label,type,components,List.of());
                    }
                }
            }
            require(!missing.containsKey(type),"missing assignment for "+type+" on "+missing.get(type));
            require(!unproven.containsKey(type),"unproven capture role "+type+" "+unproven.get(type));
            List<Fact> values=candidates(type);
            require(values.size()<2,"ambiguous "+type+": "+values.stream().map(Fact::display).toList());
            if(values.size()==1) return values.getFirst();
            Class<?> c=rawRecord(type);
            if(assemble&&c!=null&&c.getRecordComponents().length>0) {
                List<Fact> components=new ArrayList<>();
                for(var component:c.getRecordComponents()) components.add(resolve(memberType(type,component.getGenericType()),placement,label+"."+component.getName(),false));
                return fact(placement,"input",phase,label,type,components,List.of());
            }
            throw new IllegalArgumentException("unbound "+type+" at "+placement+"; legal scope "+path);
        }
        void merge(Placement placement,String label,List<Scope> paths) {
            List<Scope> live=paths.stream().filter(s->!s.terminated).toList();
            if(live.isEmpty()) { terminated=true; return; }
            Set<Type> types=new LinkedHashSet<>();
            for(Scope s:live) s.facts.stream().filter(f->!facts.contains(f)).forEach(f->types.add(f.type()));
            Fact continuation=null;
            for(Type type:types) {
                List<Fact> selected=new ArrayList<>(); String absent=null;
                for(Scope s:live) {
                    List<Fact> local=s.facts.stream().filter(f->f.type().equals(type)&&!facts.contains(f)&&!s.superseded.contains(f)).toList();
                    if(s.carrier!=null&&s.carrier.type().equals(type)) selected.add(s.carrier);
                    else if(local.size()==1) selected.add(local.getFirst());
                    else if(local.size()>1) throw new IllegalArgumentException("ambiguous capture role "+type);
                    else {
                        List<Fact> old=s.candidates(type);
                        require(old.size()<2,"ambiguous unchanged role "+type);
                        if(old.size()==1) selected.add(old.getFirst()); else absent=s.path.toString();
                    }
                }
                if(absent!=null) { missing.put(type,absent); continue; }
                if(selected.stream().distinct().count()==1) {
                    Fact sole=selected.getFirst();
                    if(!facts.contains(sole)) { supersede(type,selected); facts.add(sole); missing.remove(type); unproven.remove(type); }
                    if(live.stream().allMatch(s->sole.equals(s.carrier))) continuation=sole;
                    continue;
                }
                if(!live.stream().allMatch(s->s.carrier!=null&&s.carrier.type().equals(type))) { unproven.put(type,selected); continue; }
                Fact capture=fact(placement,"capture:"+type.getTypeName(),phase,label+".capture<"+shortName(type)+">",type,List.of(),List.of());
                supersede(type,selected);
                facts.add(capture); missing.remove(type); unproven.remove(type);
                captures.add(new Capture(placement,capture,selected)); continuation=capture;
            }
            if(continuation!=null) carrier=continuation;
            else {
                Fact incoming=carrier;
                if(!live.stream().allMatch(s->java.util.Objects.equals(s.carrier,incoming))) carrier=live.size()==1?live.getFirst().carrier:null;
            }
        }
        void supersede(Type type,List<Fact> selected) {
            for(Fact previous:facts) if(previous.type().equals(type)
                    &&(previous.equals(carrier)||selected.stream().allMatch(v->consumes(v,previous)))) superseded.add(previous);
        }
        void carry(Placement p,String label,Fact value) {
            facts.stream().filter(f->f.type().equals(value.type())&&(f.equals(carrier)||consumes(value,f))).forEach(superseded::add);
            Fact result=fact(p,"loop-result",phase,label+".result",value.type(),List.of(value),List.of());
            facts.add(result); carrier=result; missing.remove(value.type()); unproven.remove(value.type());
        }
        void join(Placement p,String label,List<Fact> members,Type declared) {
            require(members.stream().allMatch(f->f!=null),"member output required");
            boolean homogeneous=members.stream().map(Fact::type).distinct().count()==1;
            Fact result;
            if(homogeneous) {
                Type actual=new ListType(members.getFirst().type());
                require(declared==null||actual.equals(declared),"group result type mismatch: "+actual+" vs "+declared);
                result=fact(p,"product",phase,label+".aggregate",actual,members,List.of());
            } else if(declared!=null) {
                applicationType(declared);
                Scope product=new Scope(p,this); product.facts.clear(); product.superseded.clear(); product.missing.clear(); product.unproven.clear(); product.carrier=null;
                product.facts.addAll(members);
                Fact assembled=product.resolve(declared,p,label+".aggregate",true);
                require(assembled.components().size()==members.size()&&new HashSet<>(assembled.components()).equals(new HashSet<>(members)),
                        "declared group product must retain each branch role exactly once");
                result=fact(p,"product",phase,label+".aggregate",declared,assembled.components(),List.of());
            } else result=fact(p,"product",phase,label+".product",new ProductType(members.stream().map(Fact::type).toList()),members,List.of());
            internalProductType(result.type());
            if(!homogeneous) {
                facts.addAll(members);
                members.forEach(f->{missing.remove(f.type());unproven.remove(f.type());});
            }
            facts.add(result); missing.remove(result.type()); unproven.remove(result.type());
            carrier=result; products.add(new Product(p,result,members));
        }
    }

    private static Class<?> rawRecord(Type type) {
        Class<?> raw=type instanceof Class<?> c?c:type instanceof ParameterizedType p&&p.getRawType() instanceof Class<?> c?c:null;
        return raw!=null&&raw.isRecord()?raw:null;
    }

    private static Type memberType(Type owner,Type member) {
        if(member instanceof TypeVariable<?> variable&&owner instanceof ParameterizedType p) {
            TypeVariable<?>[] variables=((Class<?>)p.getRawType()).getTypeParameters();
            for(int i=0;i<variables.length;i++) if(variables[i].equals(variable)) return p.getActualTypeArguments()[i];
        }
        if(member instanceof ParameterizedType p) {
            Type[] arguments=Arrays.stream(p.getActualTypeArguments()).map(t->memberType(owner,t)).toArray(Type[]::new);
            return p.getRawType()==List.class?new ListType(arguments[0]):new AppliedType(p.getOwnerType(),p.getRawType(),List.of(arguments));
        }
        return member;
    }
    private record AppliedType(Type owner,Type raw,List<Type> arguments) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return arguments.toArray(Type[]::new); }
        @Override public Type getRawType() { return raw; }
        @Override public Type getOwnerType() { return owner; }
        @Override public boolean equals(Object other) { return other instanceof ParameterizedType p&&raw.equals(p.getRawType())&&java.util.Objects.equals(owner,p.getOwnerType())&&Arrays.equals(getActualTypeArguments(),p.getActualTypeArguments()); }
        @Override public int hashCode() { return Arrays.hashCode(getActualTypeArguments())^raw.hashCode()^java.util.Objects.hashCode(owner); }
        @Override public String getTypeName() { return raw.getTypeName()+"<"+arguments.stream().map(Type::getTypeName).collect(Collectors.joining(","))+">"; }
    }

    private static List<Node> freeze(List<Node> nodes) {
        return nodes.stream().map(n->switch(n) {
            case Choice c->new Choice(c.id(),c.operation(),c.assessment(),c.arms().stream().map(a->new Arm(a.outcome(),freeze(a.nodes()))).toList());
            case Parallel p->new Parallel(p.id(),p.output(),p.allSuccessful(),p.members().stream().map(m->new Member(m.name(),freeze(m.nodes()))).toList());
            case Fan f->new Fan(f.id(),f.element(),f.maxItems(),f.maxInFlight(),f.allSuccessful(),freeze(f.body()));
            case Loop l->new Loop(l.id(),l.test(),l.maxIterations(),l.policy(),freeze(l.body()));
            case Child c->new Child(c.id(),freezeDefinition(c.definition()));
            default->n;
        }).toList();
    }
    private static Definition<?,?> freezeDefinition(Definition<?,?> d) { return new Definition<>(d.name(),d.input(),d.output(),freeze(d.nodes()),d.deadline()); }

    private record Fragment(String entry,List<String> exits) {}
    private static final class Graph {
        final List<WorkflowNode> nodes=new ArrayList<>();
        final List<WorkflowEdge> edges=new ArrayList<>();
        final List<String> terminals=new ArrayList<>();
        final Map<Placement,Metadata> metadata;
        Graph(Map<Placement,Metadata> metadata) { this.metadata=metadata; }
        void addAdapter(String id,Type input,Type output) { nodes.add(new WorkflowNode.StepNode(id,input,output)); }
        Fragment sequence(List<Node> ast,Placement parent) {
            String entry=null; List<String> previous=new ArrayList<>();
            for(int index=0;index<ast.size();index++) {
                Node node=ast.get(index); Placement p=parent.child("node",name(node),index);
                Fragment part=emit(node,p);
                if(entry==null) entry=part.entry();
                for(String from:previous) edges.add(WorkflowEdge.sequence(from,part.entry()));
                previous=part.exits();
            }
            return new Fragment(entry,previous);
        }
        Fragment emit(Node node,Placement p) {
            String id=p.graphName(); Metadata info=metadata.get(p);
            switch(node) {
                case Choice c -> {
                    String join=p.child("join","choice",0).graphName();
                    String routing=id;
                    String declaredJoin=metadata.containsKey(p.child("join","choice",0))?join:null;
                    if(c.assessment()!=null) {
                        addAdapter(id,info.input(),Verdict.class);
                        String interpretation=p.child("evidence","interpretation",0).graphName();
                        addAdapter(interpretation,Verdict.class,Conclusion.class);
                        routing=p.child("routing","native-reading",0).graphName();
                        edges.add(WorkflowEdge.sequence(id,interpretation)); edges.add(WorkflowEdge.sequence(interpretation,routing));
                        nodes.add(new WorkflowNode.DecisionNode(routing,Conclusion.class,Conclusion.class,declaredJoin));
                    } else nodes.add(new WorkflowNode.DecisionNode(id,info.input(),info.output(),declaredJoin));
                    boolean continuing=false;
                    for(int a=0;a<c.arms().size();a++) {
                        Arm arm=c.arms().get(a); Fragment body=sequence(arm.nodes(),p.child("arm",arm.outcome().name(),a));
                        String target=body.entry()==null?join:body.entry();
                        edges.add(WorkflowEdge.conditional(routing,target,new EdgeCondition.OptionMatch(arm.outcome().name()),arm.outcome().name()));
                        if(body.entry()==null) continuing=true;
                        for(String exit:body.exits()) { edges.add(WorkflowEdge.sequence(exit,join)); continuing=true; }
                    }
                    if(continuing) { Type output=metadata.get(p.child("join","choice",0)).output(); addAdapter(join,output,output); return new Fragment(id,List.of(join)); }
                    return new Fragment(id,List.of());
                }
                case Parallel group -> {
                    String join=p.child("join","parallel",0).graphName(); nodes.add(new WorkflowNode.ForkNode(id,join));
                    for(int b=0;b<group.members().size();b++) {
                        Member member=group.members().get(b); Fragment body=sequence(member.nodes(),p.child("member",member.name(),b));
                        edges.add(WorkflowEdge.conditional(id,body.entry(),new EdgeCondition.BranchIndex(b),member.name()));
                        for(String exit:body.exits()) edges.add(WorkflowEdge.sequence(exit,join));
                    }
                    // A typed adapter prevents the legacy untyped join from imposing feeder semantics.
                    addAdapter(join,info.output(),info.output()); return new Fragment(id,List.of(join));
                }
                case Fan fan -> {
                    String join=p.child("join","fan",0).graphName(); nodes.add(new WorkflowNode.ForkNode(id,join));
                    Fragment body=sequence(fan.body(),p.child("body","item",0));
                    edges.add(WorkflowEdge.conditional(id,body.entry(),new EdgeCondition.BranchIndex(0),"manifest-item"));
                    for(String exit:body.exits()) edges.add(WorkflowEdge.sequence(exit,join));
                    addAdapter(join,info.output(),info.output()); return new Fragment(id,List.of(join));
                }
                case Loop loop -> {
                    addAdapter(id,info.input(),info.output());
                    Fragment body=sequence(loop.body(),p.child("body","loop",0));
                    String test=p.child("test",loop.test().name(),0).graphName();
                    String exit=p.child("exit","loop",0).graphName();
                    nodes.add(new WorkflowNode.LoopCheckNode(test,info.input()));
                    nodes.add(new WorkflowNode.LoopExitNode(exit));
                    edges.add(WorkflowEdge.sequence(id,body.entry()));
                    for(String tail:body.exits()) edges.add(WorkflowEdge.sequence(tail,test));
                    edges.add(WorkflowEdge.conditional(test,body.entry(),new EdgeCondition.LoopContinue(),"continue below cap"));
                    edges.add(WorkflowEdge.conditional(test,exit,new EdgeCondition.LoopExit(),"true or configured cap exit"));
                    return new Fragment(id,List.of(exit));
                }
                case End ignored -> { addAdapter(id,info.input(),info.output()); terminals.add(id); return new Fragment(id,List.of()); }
                case Child ignored -> {
                    addAdapter(id,info.input(),info.output());
                    if("false".equals(info.configuration().get("returns"))) { terminals.add(id); return new Fragment(id,List.of()); }
                    return new Fragment(id,List.of(id));
                }
                default -> { addAdapter(id,info.input(),info.output()); return new Fragment(id,List.of(id)); }
            }
        }
    }
}
