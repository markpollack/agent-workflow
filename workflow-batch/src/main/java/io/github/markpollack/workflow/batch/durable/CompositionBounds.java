package io.github.markpollack.workflow.batch.durable;

/** Memoized structural occurrence counts, without expanding referenced graphs. */
record CompositionBounds(long leaves, long composites, int depth, long logical, long scopes, long attempts) {
	static CompositionBounds combine(long localLeaves, java.util.List<CompositionBounds> children,
			ExecutionPolicy policy) {
		return combine(localLeaves, children, policy, 0);
	}

	static CompositionBounds combine(long localLeaves, java.util.List<CompositionBounds> children,
			ExecutionPolicy policy, long members) {
		long leaves = localLeaves, composites = 0;
		int depth = 0;
		long memberScopes = members;
		for (CompositionBounds child : children) {
			memberScopes = add(memberScopes, child.scopes - child.composites - 1, Long.MAX_VALUE);
			leaves = add(leaves, child.leaves, policy.maximumInvocations());
			composites = add(composites, add(1, child.composites, policy.maximumInvocations()),
					policy.maximumInvocations());
			if (child.depth >= policy.maximumDepth())
				throw new WorkflowRefusal("COMPOSITION_LIMIT", "maximum composite depth exceeded");
			depth = Math.max(depth, child.depth + 1);
		}
		long logical = add(leaves, composites, policy.maximumInvocations());
		long scopes = add(add(1, composites, Long.MAX_VALUE), memberScopes, Long.MAX_VALUE);
		if (leaves > Long.MAX_VALUE / policy.maximumAttempts())
			throw new WorkflowRefusal("COMPOSITION_LIMIT", "physical attempt bound overflow");
		return new CompositionBounds(leaves, composites, depth, logical, scopes, leaves * policy.maximumAttempts());
	}

	/**
	 * Multiply symbolic occurrences without copying graphs or enumerating possible items.
	 */
	static CompositionBounds repeatedChild(CompositionBounds child, long count) {
		return new CompositionBounds(multiply(child.leaves, count),
				add(multiply(child.composites, count), count - 1, Long.MAX_VALUE), child.depth,
				multiply(child.logical, count), multiply(child.scopes, count), multiply(child.attempts, count));
	}

	static long multiply(long a, long b) {
		try {
			return Math.multiplyExact(a, b);
		}
		catch (ArithmeticException ex) {
			throw new WorkflowRefusal("COMPOSITION_LIMIT", "symbolic occurrence overflow", ex);
		}
	}

	static CompositionBounds descriptor(DefinitionDescriptor d, java.util.Map<String, CompositionBounds> done,
			ExecutionPolicy policy) {
		var expected = new java.util.HashSet<>(d.leaves().keySet());
		expected.addAll(d.callees().keySet());
		RunIntegrity.require(
				expected.equals(d.occurrences().keySet()) && d.occurrences().values().stream().allMatch(n -> n > 0),
				"symbolic occurrence coverage");
		long leaves = 0;
		for (String node : d.leaves().keySet())
			leaves = add(leaves, d.occurrences().get(node), policy.maximumInvocations());
		var children = d.callees()
			.entrySet()
			.stream()
			.map(e -> repeatedChild(done.get(e.getValue()), d.occurrences().get(e.getKey())))
			.toList();
		return combine(leaves, children, policy, d.members());
	}

	private static long add(long a, long b, long limit) {
		if (a < 0 || b < 0 || a > limit || b > limit - a)
			throw new WorkflowRefusal("COMPOSITION_LIMIT", "logical/scope occurrence bound exceeded");
		return a + b;
	}
}
