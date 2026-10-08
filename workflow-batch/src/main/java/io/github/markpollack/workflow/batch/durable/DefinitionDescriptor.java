package io.github.markpollack.workflow.batch.durable;

import java.util.*;

/**
 * Exact registered selections for one authored definition; never hashes object internals.
 */
record DefinitionDescriptor(String authored, Map<String, String> leaves, Map<String, String> callees,
		Set<String> controls, long members, Map<String, Long> occurrences) {
	DefinitionDescriptor(String authored, Map<String, String> leaves, Map<String, String> callees) {
		this(authored, leaves, callees, Set.of(), 0);
	}

	DefinitionDescriptor(String authored, Map<String, String> leaves, Map<String, String> callees,
			Set<String> controls) {
		this(authored, leaves, callees, controls, 0);
	}

	DefinitionDescriptor(String authored, Map<String, String> leaves, Map<String, String> callees, Set<String> controls,
			long members) {
		this(authored, leaves, callees, controls, members, ordinary(leaves, callees));
	}

	private static Map<String, Long> ordinary(Map<String, String> leaves, Map<String, String> callees) {
		Map<String, Long> weights = new TreeMap<>();
		leaves.keySet().forEach(k -> weights.put(k, 1L));
		callees.keySet().forEach(k -> weights.put(k, 1L));
		return weights;
	}

	DefinitionDescriptor {
		leaves = Map.copyOf(leaves);
		callees = Map.copyOf(callees);
		controls = Set.copyOf(controls);
		occurrences = Map.copyOf(occurrences);
	}

	String identity() {
		List<String> fields = new ArrayList<>();
		fields.add("prepared-definition-v3");
		fields.add(authored);
		fields.add(Long.toString(members));
		append(fields, leaves);
		append(fields, callees);
		fields.add(Integer.toString(controls.size()));
		fields.addAll(new TreeSet<>(controls));
		new TreeMap<>(occurrences).forEach((k, v) -> {
			fields.add(k);
			fields.add(Long.toString(v));
		});
		return Digests.fields(fields.toArray(String[]::new));
	}

	private static void append(List<String> fields, Map<String, String> entries) {
		fields.add(Integer.toString(entries.size()));
		new TreeMap<>(entries).forEach((node, selection) -> {
			fields.add(node);
			fields.add(selection);
		});
	}
}
