package io.github.markpollack.workflow.batch.durable;

import java.lang.reflect.*;
import java.util.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.compiler.TypeContracts;

/** Resolves the supplied object's declared Step contract, including concrete inherited declarations. */
record StepTypes(Type input, Type output) {
    static StepTypes of(Class<?> implementation) {
        StepTypes found = find(implementation, Map.of());
        if (found == null) throw new WorkflowRefusal("STEP_CONTRACT", "concrete Step input/output declaration required: " + implementation.getName());
        try {
            TypeContracts types = new TypeContracts();
            types.contract(found.input); types.contract(found.output);
        } catch (IllegalArgumentException ex) {
            throw new WorkflowRefusal("STEP_CONTRACT", "unsupported or unresolved Step type: " + implementation.getName(), ex);
        }
        return found;
    }

    private static StepTypes find(Type declaration, Map<TypeVariable<?>, Type> inherited) {
        if (declaration == null) return null;
        Class<?> raw;
        Map<TypeVariable<?>, Type> bindings = new HashMap<>(inherited);
        if (declaration instanceof ParameterizedType parameterized) {
            raw = (Class<?>) parameterized.getRawType();
            TypeVariable<?>[] variables = raw.getTypeParameters();
            Type[] arguments = parameterized.getActualTypeArguments();
            for (int i = 0; i < variables.length; i++) bindings.put(variables[i], resolve(arguments[i], inherited));
        } else if (declaration instanceof Class<?> type) raw = type;
        else return null;
        if (raw == Step.class) {
            TypeVariable<?>[] parameters = Step.class.getTypeParameters();
            return new StepTypes(resolve(parameters[0], bindings), resolve(parameters[1], bindings));
        }
        for (Type parent : raw.getGenericInterfaces()) {
            StepTypes found = find(parent, bindings);
            if (found != null) return found;
        }
        return find(raw.getGenericSuperclass(), bindings);
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        if (type instanceof TypeVariable<?> variable) return bindings.getOrDefault(variable, variable);
        if (type instanceof ParameterizedType p) return new ConcreteType(p.getOwnerType(), p.getRawType(),
                Arrays.stream(p.getActualTypeArguments()).map(t -> resolve(t, bindings)).toArray(Type[]::new));
        return type;
    }

    private record ConcreteType(Type owner, Type raw, Type[] arguments) implements ParameterizedType {
        ConcreteType { arguments = arguments.clone(); }
        public Type[] getActualTypeArguments() { return arguments.clone(); }
        public Type getRawType() { return raw; }
        public Type getOwnerType() { return owner; }
        public String getTypeName() { return raw.getTypeName() + "<" + String.join(", ", Arrays.stream(arguments).map(Type::getTypeName).toList()) + ">"; }
        @Override public boolean equals(Object other) { return other instanceof ParameterizedType p && Objects.equals(owner,p.getOwnerType()) && raw.equals(p.getRawType()) && Arrays.equals(arguments,p.getActualTypeArguments()); }
        @Override public int hashCode() { return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ raw.hashCode(); }
    }
}
