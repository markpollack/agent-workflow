package io.github.markpollack.workflow.flows.compiler;

import java.lang.reflect.*;
import java.util.*;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.compiler.TypeContracts;

/**
 * Reads the concrete input/output declaration of a supplied Step class for registration
 * and admission. Workflows uses the result to declare workflow contracts; preparation
 * uses the same memoized declaration before admission.
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
public record StepTypes(Type input, Type output) {

	private static final ClassValue<StepTypes> DECLARATIONS = new ClassValue<>() {
		@Override
		protected StepTypes computeValue(Class<?> implementation) {
			List<StepTypes> declarations = new ArrayList<>();
			find(implementation, Map.of(), declarations);
			if (declarations.isEmpty())
				throw new IllegalArgumentException("concrete Step declaration required: " + implementation.getName());
			TypeContracts contracts = new TypeContracts();
			for (StepTypes declaration : declarations) {
				contracts.contract(declaration.input());
				contracts.contract(declaration.output());
				if (!declaration.equals(declarations.getFirst()))
					throw new IllegalArgumentException("conflicting Step contracts: " + implementation.getName());
			}
			return declarations.getFirst();
		}
	};

	/**
	 * Resolve a supplied implementation's declaration, then validate both durable value
	 * contracts. Parameterized types retain their arguments; they are not reduced to
	 * Class.
	 * @param implementation the actual registered object's class
	 * @return concrete input and output Types
	 * @throws IllegalArgumentException for absent, raw, conflicting, unresolved or
	 * unsupported types
	 */
	public static StepTypes of(Class<?> implementation) {
		return DECLARATIONS.get(Objects.requireNonNull(implementation));
	}

	/**
	 * Walk interface/superclass signatures, carrying bindings for each declaration's type
	 * variables. A generic base is usable only when the concrete implementation fixes all
	 * variables needed by Step. Merely constructing Base&lt;Request&gt; does not do that.
	 */
	private static void find(Type declaration, Map<TypeVariable<?>, Type> inherited, List<StepTypes> found) {
		if (declaration == null)
			return;
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
			return;
		if (raw == Step.class) {
			TypeVariable<?>[] parameters = Step.class.getTypeParameters();
			found.add(new StepTypes(resolve(parameters[0], bindings), resolve(parameters[1], bindings)));
			return;
		}
		for (Type parent : raw.getGenericInterfaces())
			find(parent, bindings, found);
		find(raw.getGenericSuperclass(), bindings, found);
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
