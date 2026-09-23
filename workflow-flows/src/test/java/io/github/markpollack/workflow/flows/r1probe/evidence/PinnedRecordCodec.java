package io.github.markpollack.workflow.flows.r1probe.evidence;

import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.jury.interpretation.Interpretation;

final class PinnedRecordCodec {

	static final String CODEC = "jackson-record";
	static final String VERSION = "2.22.2/probe-1";
	static final String CONFIGURATION = "strict-record-and-native-judge-0.17.0";

	private final ObjectMapper mapper = JsonMapper.builder()
		.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
		.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	record Payload(String declaredType, String codec, String version, String configuration, byte[] bytes) {
		Payload {
			bytes = bytes.clone();
		}

		@Override
		public byte[] bytes() {
			return bytes.clone();
		}
	}

	Payload encode(Object value, TypeReference<?> declaration) {
		JavaType type = mapper.constructType(declaration);
		requireSupported(type, new HashSet<>());
		requireValue(value, type);
		try {
			byte[] bytes = mapper.writerFor(type).writeValueAsBytes(value);
			requireShape(mapper.readTree(bytes), type, false);
			Object reconstructed = mapper.readValue(bytes, type);
			if (!equivalent(value, reconstructed, type)) {
				throw new IllegalArgumentException("lossless encoding refused: reconstructed value differs");
			}
			return new Payload(type.toCanonical(), CODEC, VERSION, CONFIGURATION,
					bytes);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("encode failed for " + type.toCanonical(), ex);
		}
	}

	<T> T decode(Payload payload, TypeReference<T> declaration) {
		JavaType type = mapper.constructType(declaration);
		requireSupported(type, new HashSet<>());
		requireIdentity("declared type", type.toCanonical(), payload.declaredType());
		requireIdentity("codec", CODEC, payload.codec());
		requireIdentity("codec version", VERSION, payload.version());
		requireIdentity("codec configuration", CONFIGURATION, payload.configuration());
		try {
			requireShape(mapper.readTree(payload.bytes()), type, false);
			T value = mapper.readValue(payload.bytes(), type);
			requireValue(value, type);
			return value;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("decode failed for " + type.toCanonical(), ex);
		}
	}

	private static void requireIdentity(String field, String expected, String actual) {
		if (!expected.equals(actual)) {
			throw new IllegalArgumentException(field + " incompatible: expected " + expected + ", stored " + actual);
		}
	}

	private boolean equivalent(Object original, Object reconstructed, JavaType type) throws Exception {
		if (original == null || reconstructed == null) {
			return original == reconstructed;
		}
		Class<?> raw = type.getRawClass();
		if (nativeEvidence(raw)) {
			return mapper.readTree(mapper.writeValueAsBytes(original))
				.equals(mapper.readTree(mapper.writeValueAsBytes(reconstructed)));
		}
		if (scalar(raw)) {
			return original.equals(reconstructed);
		}
		if (raw == List.class) {
			List<?> left = (List<?>) original;
			List<?> right = (List<?>) reconstructed;
			if (left.size() != right.size()) {
				return false;
			}
			for (int index = 0; index < left.size(); index++) {
				if (!equivalent(left.get(index), right.get(index), type.containedType(0))) {
					return false;
				}
			}
			return true;
		}
		for (RecordComponent component : raw.getRecordComponents()) {
			if (!equivalent(component.getAccessor().invoke(original), component.getAccessor().invoke(reconstructed),
					componentType(type, component))) {
				return false;
			}
		}
		return true;
	}

	private void requireShape(JsonNode node, JavaType type, boolean nullable) {
		Class<?> raw = type.getRawClass();
		if (node == null || (node.isNull() && !nullable)) {
			throw new IllegalArgumentException("missing or null value for " + type.toCanonical());
		}
		if (node.isNull() || nativeEvidence(raw)) {
			return;
		}
		if (raw == String.class || raw.isEnum()) {
			if (!node.isTextual()) {
				throw new IllegalArgumentException("expected JSON string for " + type.toCanonical());
			}
		}
		else if (raw == boolean.class || raw == Boolean.class) {
			if (!node.isBoolean()) {
				throw new IllegalArgumentException("expected JSON boolean");
			}
		}
		else if (scalar(raw)) {
			if (!node.isNumber() || ((raw != double.class && raw != Double.class && raw != float.class
					&& raw != Float.class) && !node.isIntegralNumber())) {
				throw new IllegalArgumentException("expected JSON number for " + type.toCanonical());
			}
		}
		else if (raw == List.class) {
			if (!node.isArray()) {
				throw new IllegalArgumentException("expected JSON array");
			}
			for (JsonNode element : node) {
				requireShape(element, type.containedType(0), false);
			}
		}
		else {
			if (!node.isObject()) {
				throw new IllegalArgumentException("expected JSON object");
			}
			for (RecordComponent component : raw.getRecordComponents()) {
				String property = mapper.getDeserializationConfig().introspect(type).findProperties().stream()
					.filter(candidate -> candidate.getInternalName().equals(component.getName()))
					.map(candidate -> candidate.getName()).findFirst()
					.orElseThrow(() -> new IllegalArgumentException("unsupported record property " + component.getName()));
				requireShape(node.get(property), componentType(type, component),
						raw == NativeJudgeProbe.Assessment.class);
			}
		}
	}

	private void requireSupported(JavaType type, Set<JavaType> seen) {
		if (!seen.add(type)) {
			return;
		}
		Class<?> raw = type.getRawClass();
		if (scalar(raw) || nativeEvidence(raw)) {
			return;
		}
		if (raw == List.class && type.containedTypeCount() == 1) {
			requireSupported(type.containedType(0), seen);
			return;
		}
		if (raw.isRecord()) {
			for (RecordComponent component : raw.getRecordComponents()) {
				requireSupported(componentType(type, component), seen);
			}
			return;
		}
		throw new IllegalArgumentException("unsupported declared type " + type.toCanonical());
	}

	private void requireValue(Object value, JavaType type) {
		requireValue(value, type, false);
	}

	private void requireValue(Object value, JavaType type, boolean nullable) {
		Class<?> raw = type.getRawClass();
		if (value == null) {
			if (!nullable || raw.isPrimitive()) {
				throw new IllegalArgumentException("null value forbidden for " + type.toCanonical());
			}
			return;
		}
		Class<?> expected = raw == int.class ? Integer.class : raw == long.class ? Long.class
				: raw == boolean.class ? Boolean.class : raw == double.class ? Double.class
				: raw == float.class ? Float.class : raw;
		if (!expected.isInstance(value)) {
			throw new IllegalArgumentException("value type mismatch: expected " + type.toCanonical()
					+ ", received " + value.getClass().getTypeName());
		}
		if (nativeEvidence(raw) || scalar(raw)) {
			return;
		}
		if (raw == List.class) {
			for (Object element : (List<?>) value) {
				requireValue(element, type.containedType(0));
			}
		}
		else {
			for (RecordComponent component : raw.getRecordComponents()) {
				try {
					requireValue(component.getAccessor().invoke(value), componentType(type, component),
							raw == NativeJudgeProbe.Assessment.class);
				}
				catch (ReflectiveOperationException ex) {
					throw new IllegalArgumentException("cannot read component " + component.getName(), ex);
				}
			}
		}
	}

	private JavaType componentType(JavaType owner, RecordComponent component) {
		return mapper.getTypeFactory().resolveMemberType(component.getGenericType(), owner.getBindings());
	}

	private static boolean nativeEvidence(Class<?> raw) {
		return raw == Verdict.class || raw == Interpretation.class;
	}

	private static boolean scalar(Class<?> raw) {
		return raw == String.class || raw == int.class || raw == Integer.class || raw == long.class
				|| raw == Long.class || raw == boolean.class || raw == Boolean.class || raw == double.class
				|| raw == Double.class || raw == float.class || raw == Float.class || raw.isEnum();
	}

}
