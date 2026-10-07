package io.github.markpollack.workflow.batch.durable;

import java.util.*;

/**
 * Exact registered selections for one authored definition; never hashes object internals.
 */
record DefinitionDescriptor(String authored, Map<String, String> leaves, Map<String, String> callees,
		Set<String> controls, long members) {
	DefinitionDescriptor(String authored, Map<String, String> leaves, Map<String, String> callees) {
		this(authored, leaves, callees, Set.of(), 0);
	}

	DefinitionDescriptor(String authored, Map<String, String> leaves, Map<String, String> callees,
			Set<String> controls) {
		this(authored, leaves, callees, controls, 0);
	}

	DefinitionDescriptor {
		leaves = Map.copyOf(leaves);
		callees = Map.copyOf(callees);
		controls = Set.copyOf(controls);
	}

	String identity() {
		List<String> fields = new ArrayList<>();
		fields.add("prepared-definition-v2");
		fields.add(authored);
		fields.add(Long.toString(members));
		append(fields, leaves);
		append(fields, callees);
		fields.add(Integer.toString(controls.size()));
		fields.addAll(new TreeSet<>(controls));
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
