package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static io.github.markpollack.workflow.flows.compiler.WorkflowModel.*;

/** Transfers caller construction into owned immutable data before any semantic analysis. */
final class DefinitionOwnership {
    private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
    private int depth;
    private final java.util.Map<Definition<?,?>, Definition<?,?>> definitions = new IdentityHashMap<>();

    static Definition<?,?> acquire(Definition<?,?> input) {
        return new DefinitionOwnership().definition(input);
    }

    private <T> T within(Object input, Supplier<T> copy) {
        if (input == null) throw new IllegalArgumentException("missing definition element");
        if (depth >= 256) throw new IllegalArgumentException("compiler containment depth limit exceeded");
        if (!active.add(input)) throw new IllegalArgumentException("cyclic definition containment or type");
        depth++;
        try { return copy.get(); }
        finally { depth--; active.remove(input); }
    }

    private Definition<?,?> definition(Definition<?,?> d) {
        if (definitions.containsKey(d)) return definitions.get(d);
        Definition<?,?> owned = within(d, () -> new Definition<>(d.name(), type(d.input()), type(d.output()), nodes(d.nodes()), d.deadline()));
        definitions.put(d, owned);
        return owned;
    }

    private <T,R> List<R> copy(List<T> values, java.util.function.Function<T,R> copier) {
        return within(values, () -> {
            int count=values.size();
            if (count<0 || count>100_000) throw new IllegalArgumentException("compiler collection size limit exceeded");
            List<R> result=new ArrayList<>(count);
            for (int i=0;i<count;i++) result.add(copier.apply(values.get(i)));
            return List.copyOf(result);
        });
    }

    private List<Node> nodes(List<Node> nodes) { return copy(nodes, this::node); }

    private Node node(Node n) {
        return within(n, () -> switch(n) {
            case Call c -> new Call(c.id(), op(c.operation()));
            case Choice c -> new Choice(c.id(), c.operation()==null?null:op(c.operation()),
                    c.assessment()==null?null:new Assessment<>(c.assessment().name(),type(c.assessment().input())),
                    copy(c.arms(),a -> within(a,()->new Arm(a.outcome(),nodes(a.nodes())))));
            case Parallel p -> new Parallel(p.id(),p.output()==null?null:type(p.output()),p.allSuccessful(),
                    copy(p.members(),m -> within(m,()->new Member(m.name(),nodes(m.nodes())))));
            case Fan f -> new Fan(f.id(),f.element()==null?null:type(f.element()),f.maxItems(),f.maxInFlight(),f.allSuccessful(),nodes(f.body()));
            case Loop l -> new Loop(l.id(),booleanOp(l.test()),l.maxIterations(),l.policy(),nodes(l.body()));
            case Child c -> c.reference() == null ? new Child(c.id(),definition(c.definition())) : new Child(c.id(),c.reference());
            case Timer t -> new Timer(t.id(),t.duration());
            case End e -> new End(e.terminal(),e.reason());
        });
    }

    private Op<?,?> op(Op<?,?> op) {
        return within(op,()->Op.declared(op.name(),type(op.input()),type(op.output())));
    }
    @SuppressWarnings("unchecked") // The original generic is a witness only; semantic analysis checks Boolean output.
    private Op<?,Boolean> booleanOp(Op<?,Boolean> op) { return (Op<?,Boolean>)op(op); }

    static Type ownType(Type type) { return new DefinitionOwnership().type(type); }
    private Type type(Type t) {
        if (t instanceof Class<?>) return t;
        return within(t,()-> {
            if (!(t instanceof ParameterizedType p)) throw new IllegalArgumentException("unsupported unresolved application type");
            Type raw=p.getRawType();
            if (!(raw instanceof Class<?> c)) throw new IllegalArgumentException("concrete raw type required");
            Type[] arguments=p.getActualTypeArguments().clone();
            Type owner=p.getOwnerType();
            if (c.getTypeParameters().length==0 || arguments.length!=c.getTypeParameters().length) throw new IllegalArgumentException("generic arity mismatch");
            List<Type> copied=new ArrayList<>();
            for(Type arg:arguments) {
                Type ownedArgument=type(arg);
                if(ownedArgument instanceof Class<?> argumentClass && argumentClass.isPrimitive())
                    throw new IllegalArgumentException("primitive generic argument");
                copied.add(ownedArgument);
            }
            for(int i=0;i<c.getTypeParameters().length;i++) {
                for(Type bound:c.getTypeParameters()[i].getBounds()) {
                    if(!(bound instanceof Class<?> boundClass))
                        throw new IllegalArgumentException("unsupported dependent generic bound: "+bound);
                    Type argument=copied.get(i);
                    Class<?> actual=argument instanceof Class<?> a?a:(Class<?>)((ParameterizedType)argument).getRawType();
                    if(!boundClass.isAssignableFrom(actual)) throw new IllegalArgumentException("generic bound violation: "+boundClass.getName());
                }
            }
            Type ownedOwner=owner==null?null:type(owner);
            if (c.getDeclaringClass()==null && ownedOwner!=null) throw new IllegalArgumentException("foreign generic owner");
            if (c.getDeclaringClass()!=null && ownedOwner!=null) {
                Type ownerRaw=ownedOwner instanceof ParameterizedType o?o.getRawType():ownedOwner;
                if (ownerRaw!=c.getDeclaringClass()) throw new IllegalArgumentException("foreign generic owner");
            }
            return new OwnedType(ownedOwner,c,copied);
        });
    }

    private record OwnedType(Type owner,Class<?> raw,List<Type> arguments) implements ParameterizedType {
        OwnedType { arguments=List.copyOf(arguments); }
        @Override public Type[] getActualTypeArguments() { return arguments.toArray(Type[]::new); }
        @Override public Type getRawType() { return raw; }
        @Override public Type getOwnerType() { return owner; }
        @Override public String getTypeName() { return raw.getTypeName()+"<"+String.join(",",arguments.stream().map(Type::getTypeName).toList())+">"; }
        @Override public boolean equals(Object other) { return other instanceof ParameterizedType p && raw.equals(p.getRawType()) && Objects.equals(owner,p.getOwnerType()) && Arrays.equals(getActualTypeArguments(),p.getActualTypeArguments()); }
        @Override public int hashCode() { return Arrays.hashCode(getActualTypeArguments())^raw.hashCode()^Objects.hashCode(owner); }
    }
}
