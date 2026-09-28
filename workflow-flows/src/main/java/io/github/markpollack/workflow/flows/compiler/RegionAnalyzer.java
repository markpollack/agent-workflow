package io.github.markpollack.workflow.flows.compiler;

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

import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.Interpretation;
import io.github.markpollack.judge.jury.interpretation.VerdictReading;

import io.github.markpollack.workflow.flows.workflow.EdgeCondition;
import io.github.markpollack.workflow.flows.workflow.WorkflowEdge;
import io.github.markpollack.workflow.flows.workflow.WorkflowGraph;
import io.github.markpollack.workflow.flows.workflow.WorkflowNode;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/** Forward region analysis: binding/provenance transfers and authored continuation effects. */
final class RegionAnalyzer {
    private final TypeContracts contracts=new TypeContracts();
    private final List<Binding> bindings=new ArrayList<>();
    private final List<Capture> captures=new ArrayList<>();
    private final List<Product> products=new ArrayList<>();
    private final List<LoopContract> loops=new ArrayList<>();
    private final Map<Placement,Metadata> metadata=new LinkedHashMap<>();
    private final Map<SummaryKey,RegionSummary> summaries=new LinkedHashMap<>();
    private final Map<SummaryKey,Metadata> phaseMetadata=new LinkedHashMap<>();
    private Type boundaryOutput;

    record Analysis(Definition<?,?> definition, Map<Placement,Metadata> metadata, List<Binding> bindings,
                    List<Capture> captures,List<Product> products,List<LoopContract> loops,
                    Map<SummaryKey,RegionSummary> summaries,Map<SummaryKey,Metadata> phaseMetadata) {}

    static Analysis analyze(Definition<?,?> owned) { return new RegionAnalyzer().run(owned); }
    private Analysis run(Definition<?,?> definition) {
        require(definition.name()!=null&&!definition.name().isBlank(),"workflow name required");
        applicationType(definition.input()); applicationType(definition.output());
        finite(definition.deadline(),false,"finite positive workflow deadline required");
        boundaryOutput=definition.output();
        Placement root=Coordinates.root(definition);
        RegionSummary summary=walk(definition.nodes(),new Scope(root,definition.input()),root,false);
        require(!summary.continues(),"missing terminal or dangling path");
        require(!definition.nodes().isEmpty(),"empty definition");
        return new Analysis(definition,Map.copyOf(metadata),List.copyOf(bindings),List.copyOf(captures),
                List.copyOf(products),List.copyOf(loops),Map.copyOf(summaries),Map.copyOf(phaseMetadata));
    }

    private void applicationType(Type type) { contracts.applicationType(type); }
    private void internalProductType(Type type) { contracts.internalType(type); }

    private static void finite(Duration duration,boolean zero,String message) {
        require(duration!=null&&!duration.isNegative()&&(zero||!duration.isZero()),message);
        try { duration.toNanos(); } catch(ArithmeticException ex) { throw new IllegalArgumentException(message+": overflow",ex); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
    private RegionSummary walk(List<Node> nodes,Scope scope,Placement parent,boolean member) {
        require(nodes!=null,"missing sequence");
        int sequenceCaptures=captures.size(), sequenceProducts=products.size();
        List<Placement> normalExits=List.of();
        Set<String> labels=new HashSet<>();
        for(int index=0;index<nodes.size();index++) {
            Node node=nodes.get(index);
            require(node!=null,"null node"); require(!scope.terminated,"unreachable authored work");
            String label=Coordinates.name(node);
            require(label!=null&&!label.isBlank(),"placement name required");
            require(node instanceof End||labels.add(label),"duplicate authored placement "+label);
            Placement placement=Coordinates.node(parent,node,index);
            int nodeCaptures=captures.size(), nodeProducts=products.size();
            Set<Terminal> effects=new LinkedHashSet<>();
            Set<Capability> required=new LinkedHashSet<>(); required.add(Coordinates.capability(node));
            switch(node) {
                case Call call -> {
                    require(call.operation()!=null,"missing operation");
                    call(scope,placement,call.id(),call.operation().input(),call.operation().output(),false);
                }
                case Timer timer -> {
                    finite(timer.duration(),true,"finite nonnegative timer required");
                    meta(scope.phase,placement,"timer",typeOf(scope.carrier),typeOf(scope.carrier),Map.of("duration",timer.duration().toString()));
                }
                case Child child -> {
                    require(child.definition()!=null,"unresolved child");
                    Compilation<?,?> validated=StructuredWorkflowCompiler.compileOwned(child.definition());
                    call(scope,placement,child.id(),validated.input(),validated.output(),false);
                    RegionSummary childSummary=validated.summaries().get(new SummaryKey(Coordinates.root(child.definition()),"root"));
                    boolean returns=childSummary.terminals().contains(Terminal.SUCCEEDED);
                    if(childSummary.terminals().stream().anyMatch(t->t!=Terminal.SUCCEEDED)) effects.add(Terminal.FAILED);
                    required.addAll(childSummary.capabilities());
                    scope.terminated=!returns;
                    meta(scope.phase,placement,"child",validated.input(),validated.output(),Map.of("definition",validated.definition().name(),"returns",Boolean.toString(returns)));
                }
                case End end -> {
                    require(!member,"root terminal inside member/body");
                    require(end.terminal()!=null,"terminal intent required");
                    if(end.terminal()==Terminal.SUCCEEDED) {
                        require(scope.carrier!=null&&scope.carrier.type().equals(boundaryOutput),
                                "successful output disagreement: expected "+boundaryOutput+", current "+(scope.carrier==null?"none":scope.carrier.type()));
                        applicationType(scope.carrier.type());
                    } else require(end.reason()!=null&&!end.reason().isBlank(),"non-success terminal reason required");
                    meta(scope.phase,placement,"terminal",typeOf(scope.carrier),typeOf(scope.carrier),Map.of("intent",end.terminal().name(),"value",scope.carrier==null?"none":scope.carrier.display(),"reason",end.reason()==null?"":end.reason()));
                    scope.terminated=true; effects.add(end.terminal());
                }
                case Choice choice -> {
                    require((choice.operation()==null)!=(choice.assessment()==null),"exactly one decision/assessment contract required");
                    Class<?> domain;
                    if(choice.operation()!=null) {
                        require(choice.operation().output() instanceof Class<?> c&&c.isEnum(),"concrete enum decision required");
                        domain=(Class<?>)choice.operation().output();
                        call(scope,placement,choice.id(),choice.operation().input(),domain,true);
                    } else {
                        domain=VerdictReading.class;
                        call(scope,placement,choice.id(),choice.assessment().input(),Verdict.class,true);
                        call(scope,placement.child("evidence","interpretation",0),choice.id()+"Reading",Verdict.class,Interpretation.class,true);
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
                        RegionSummary armSummary=walk(arm.nodes(),local,path,member);
                        effects.addAll(armSummary.terminals()); required.addAll(armSummary.capabilities()); arms.add(local);
                    }
                    require(seen.equals(expected),"missing choice outcome: expected "+expected+", received "+seen);
                    scope.merge(placement,choice.id(),arms);
                    if(!scope.terminated) meta(scope.phase,placement.child("join","choice",0),"exclusive-join",typeOf(scope.carrier),typeOf(scope.carrier),Map.of());
                    meta(scope.phase,placement,choice.assessment()==null?"decision":"verdict",choice.operation()!=null?choice.operation().input():choice.assessment().input(),choice.assessment()==null?domain:Verdict.class,
                            choice.assessment()==null?Map.of():Map.of("support","SUPPORTED","reading","non-null"));
                    if(choice.assessment()!=null) meta(scope.phase,placement.child("routing","native-reading",0),"native-route",Interpretation.class,VerdictReading.class,Map.of("support","SUPPORTED","reading","non-null"));
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
                        Scope local=scope.fork(path); RegionSummary memberSummary=walk(branch.nodes(),local,path,true);
                        results.add(memberSummary.requireNormalResult("parallel member"));
                        effects.addAll(memberSummary.terminals()); required.addAll(memberSummary.capabilities());
                    }
                    scope.join(placement,parallel.id(),results,parallel.output());
                    meta(scope.phase,placement,"parallel",null,scope.carrier.type(),Map.of("policy","allSuccessful","order","declaration"));
                    meta(scope.phase,placement.child("join","parallel",0),"typed-join",scope.carrier.type(),scope.carrier.type(),Map.of("policy","allSuccessful"));
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
                    RegionSummary bodySummary=walk(fan.body(),local,path,true);
                    bodySummary.requireNormalResult("fan body");
                    effects.addAll(bodySummary.terminals()); required.addAll(bodySummary.capabilities());
                    scope.join(placement,fan.id(),List.of(local.carrier),new ListType(local.carrier.type()));
                    meta(scope.phase,placement,"forEach",feeder.type(),scope.carrier.type(),Map.of("maxItems",""+fan.maxItems(),"maxInFlight",""+fan.maxInFlight(),"membership","immutable-ordered"));
                    meta(scope.phase,placement.child("join","fan",0),"typed-join",scope.carrier.type(),scope.carrier.type(),Map.of("order","manifest"));
                }
                case Loop loop -> {
                    require(loop.maxIterations()>0,"positive loop bound required");
                    require(loop.policy()!=null,"loop cap policy required");
                    require(loop.test()!=null&&Boolean.class.equals(loop.test().output()),"named Boolean loop test required");
                    require(loop.body()!=null&&!loop.body().isEmpty(),"one nonempty loop body required");
                    Placement path=placement.child("body","loop",0);
                    Scope first=scope.fork(path); first.phase=scope.phase+"/first";
                    RegionSummary firstSummary=walk(loop.body(),first,path,true);
                    Fact firstResult=firstSummary.requireNormalResult("loop body");
                    effects.addAll(firstSummary.terminals()); required.addAll(firstSummary.capabilities());
                    call(first,placement.child("test",loop.test().name(),0),loop.test().name(),loop.test().input(),Boolean.class,true);
                    Scope carried=scope.fork(path); carried.phase=scope.phase+"/carried";
                    carried.carry(placement,loop.id(),firstResult);
                    RegionSummary carriedSummary=walk(loop.body(),carried,path,true);
                    Fact carriedResult=carriedSummary.requireNormalResult("loop carried body");
                    require(carriedResult.type().equals(firstResult.type()),"loop carried output type changes");
                    effects.addAll(carriedSummary.terminals()); required.addAll(carriedSummary.capabilities());
                    if(loop.policy()==LimitPolicy.FAIL) effects.add(Terminal.FAILED);
                    call(carried,placement.child("test",loop.test().name(),0),loop.test().name(),loop.test().input(),Boolean.class,true);
                    scope.carry(placement,loop.id(),carried.carrier);
                    loops.add(new LoopContract(placement,loop.maxIterations(),loop.policy(),scope.carrier.type()));
                    meta(scope.phase,placement,"repeatUntil",null,scope.carrier.type(),Map.of("maximum",""+loop.maxIterations(),"cap",loop.policy().name(),"test",loop.test().name()));
                    meta(scope.phase,placement.child("exit","loop",0),"loop-exit",null,scope.carrier.type(),Map.of("cap",loop.policy().name()));
                }
            }
            normalExits=scope.terminated?List.of():List.of(Coordinates.normalExit(node,placement));
            RegionSummary nodeSummary=new RegionSummary(placement,scope.phase,!scope.terminated,
                    scope.terminated?null:scope.carrier,normalExits,effects,
                    captures.subList(nodeCaptures,captures.size()),products.subList(nodeProducts,products.size()),
                    required,phaseMetadata.get(new SummaryKey(placement,scope.phase)).configuration());
            summaries.put(new SummaryKey(placement,scope.phase),nodeSummary);
            scope.effects.addAll(effects); scope.capabilities.addAll(required);
        }
        RegionSummary summary=new RegionSummary(parent,scope.phase,!scope.terminated,
                scope.terminated?null:scope.carrier,normalExits,scope.effects,
                captures.subList(sequenceCaptures,captures.size()),products.subList(sequenceProducts,products.size()),
                scope.capabilities,Map.of());
        summaries.put(new SummaryKey(parent,scope.phase),summary);
        return summary;
    }

    private void meta(String phase,Placement placement,String kind,Type input,Type output,Map<String,String> config) {
        Metadata next=new Metadata(kind,input,output,config);
        phaseMetadata.put(new SummaryKey(placement,phase),next);
        List<Metadata> templates=phaseMetadata.entrySet().stream().filter(e->e.getKey().placement().equals(placement)).map(Map.Entry::getValue).toList();
        Map<String,String> common=new LinkedHashMap<>(config);
        Type commonInput=input,commonOutput=output;
        for(Metadata template:templates) {
            if(!java.util.Objects.equals(commonInput,template.input())) commonInput=null;
            if(!java.util.Objects.equals(commonOutput,template.output())) commonOutput=null;
            common.entrySet().removeIf(e->!java.util.Objects.equals(template.configuration().get(e.getKey()),e.getValue()));
        }
        metadata.put(placement,new Metadata(kind,commonInput,commonOutput,common));
    }
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
        meta(scope.phase,placement,control?"control":"operation",input,output,Map.of());
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
        final Set<Terminal> effects=new LinkedHashSet<>();
        final Set<Capability> capabilities=new LinkedHashSet<>();
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

}
