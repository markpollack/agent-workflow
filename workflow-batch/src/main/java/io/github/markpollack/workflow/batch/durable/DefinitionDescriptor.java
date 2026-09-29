package io.github.markpollack.workflow.batch.durable;

import java.util.*;

/**
 * Exact registered selections for one authored definition; never hashes object internals.
 */
record DefinitionDescriptor(String authored, Map<String, String> leaves, Map<String, String> callees) {
	DefinitionDescriptor {
		leaves = Map.copyOf(leaves);
		callees = Map.copyOf(callees);
	}

	String identity() {
		List<String> fields = new ArrayList<>();
		fields.add("prepared-definition-v1");
		fields.add(authored);
		append(fields, leaves);
		append(fields, callees);
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
