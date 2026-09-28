package io.github.markpollack.workflow.batch.durable;

import java.lang.reflect.*;
import java.util.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.compiler.TypeContracts;

/**
 * Reads the concrete input/output declaration of a supplied Step class for registration
 * and admission. ApplicationDeployment uses the result to select workflow contracts;
 * ResolvedApplication repeats the check against the actual supplied instance before use.
 * <p>
 * Java erases method dispatch but retains generic class/interface signatures. Walking
 * those signatures can resolve Step&lt;Request, List&lt;Reply&gt;&gt; and a concrete
 * subclass of a generic base. It cannot recover type arguments supplied only at an erased
 * object construction site. Raw steps, unresolved variables and lambda implementations
 * without a concrete signature refuse rather than falling back to Object.
 * <p>
 * This resolves declarations only. TypeContracts validates supported durable shapes; the
 * workflow analyzer derives input bindings and path captures from those full Types. No
 * step is constructed or invoked, and no public type-hint method is consulted.
 */
record StepTypes(Type input, Type output) {

	/**
	 * Resolve a supplied implementation's declaration, then validate both durable value
	 * contracts. Parameterized types retain their arguments; they are not reduced to
	 * Class.
	 * @param implementation the actual registered object's class
	 * @return concrete input and output Types
	 * @throws WorkflowRefusal with STEP_CONTRACT for absent, raw, unresolved or
	 * unsupported types
	 */
	static StepTypes of(Class<?> implementation) {
		StepTypes found = find(implementation, Map.of());
		if (found == null)
			throw new WorkflowRefusal("STEP_CONTRACT",
					"concrete Step input/output declaration required: " + implementation.getName());
		try {
			TypeContracts types = new TypeContracts();
			types.contract(found.input);
			types.contract(found.output);
		}
		catch (IllegalArgumentException ex) {
			throw new WorkflowRefusal("STEP_CONTRACT",
					"unsupported or unresolved Step type: " + implementation.getName(), ex);
		}
		return found;
	}

	/**
	 * Walk interface/superclass signatures, carrying bindings for each declaration's type
	 * variables. A generic base is usable only when the concrete implementation fixes all
	 * variables needed by Step. Merely constructing Base&lt;Request&gt; does not do that.
	 */
	private static StepTypes find(Type declaration, Map<TypeVariable<?>, Type> inherited) {
		if (declaration == null)
			return null;
		Class<?> raw;
		Map<TypeVariable<?>, Type> bindings = new HashMap<>(inherited);
		if (declaration instanceof ParameterizedType parameterized) {
			raw = (Class<?>) parameterized.getRawType();
			TypeVariable<?>[] variables = raw.getTypeParameters();
			Type[] arguments = parameterized.getActualTypeArguments();
			for (int i = 0; i < variables.length; i++)
				bindings.put(variables[i], resolve(arguments[i], inherited));
		}
		else if (declaration instanceof Class<?> type)
			raw = type;
		else
			return null;
		if (raw == Step.class) {
			TypeVariable<?>[] parameters = Step.class.getTypeParameters();
			return new StepTypes(resolve(parameters[0], bindings), resolve(parameters[1], bindings));
		}
		for (Type parent : raw.getGenericInterfaces()) {
			StepTypes found = find(parent, bindings);
			if (found != null)
				return found;
		}
		return find(raw.getGenericSuperclass(), bindings);
	}

	/**
	 * Substitute inherited variables inside a declared parameterized type. Remaining
	 * variables deliberately survive so TypeContracts can refuse unresolved declarations.
	 */
	private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
		if (type instanceof TypeVariable<?> variable)
			return bindings.getOrDefault(variable, variable);
		if (type instanceof ParameterizedType p)
			return new ConcreteType(p.getOwnerType(), p.getRawType(),
					Arrays.stream(p.getActualTypeArguments()).map(t -> resolve(t, bindings)).toArray(Type[]::new));
		return type;
	}

	private record ConcreteType(Type owner, Type raw, Type[] arguments) implements ParameterizedType {
		ConcreteType {
			arguments = arguments.clone();
		}

		public Type[] getActualTypeArguments() {
			return arguments.clone();
		}

		public Type getRawType() {
			return raw;
		}

		public Type getOwnerType() {
			return owner;
		}

		public String getTypeName() {
			return raw.getTypeName() + "<" + String.join(", ", Arrays.stream(arguments).map(Type::getTypeName).toList())
					+ ">";
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof ParameterizedType p && Objects.equals(owner, p.getOwnerType())
					&& raw.equals(p.getRawType()) && Arrays.equals(arguments, p.getActualTypeArguments());
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ raw.hashCode();
		}
	}
}
