package io.github.markpollack.workflow.flows.compiler;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;


/** Fixed type, configured wire-shape and value contract used by compilation and runtime encoding. */
public final class TypeContracts {
    /** Fixed implementation/configuration identity, separate from application type identity. */
    public record CodecIdentity(String name, String version, String configuration) {}
    /** A declared application contract and its configured shape, distinct from executable identity. */
    public record Contract(String javaType, String shapeDigest, CodecIdentity codec) {}
    public Contract contract(Type declaration) {
        Type owned=DefinitionOwnership.ownType(declaration);
        applicationType(owned);
        StringBuilder shape=new StringBuilder();
        describe(mapper.constructType(owned),new HashSet<>(),shape);
        return new Contract(owned.getTypeName(),IdentityEncoding.digest(shape.toString()),identity());
    }
    private void describe(JavaType type,Set<JavaType> seen,StringBuilder out) {
        IdentityEncoding.field(out,type.toCanonical());
        if(!seen.add(type)) return;
        Class<?> raw=type.getRawClass();
        if(nativeEvidence(raw)) { IdentityEncoding.field(out,"native-judge-0.18-result-json-v6"); IdentityEncoding.field(out,NativeVerdictCodec.identity()); return; }
        if(raw==List.class) { describe(type.containedType(0),seen,out); return; }
        if(raw.isEnum()) {
            for(Object value:raw.getEnumConstants()) IdentityEncoding.field(out,mapper.valueToTree(value).toString());
        } else if(raw.isRecord()) {
            Map<String,String> properties=recordProperties(type);
            for(RecordComponent component:raw.getRecordComponents()) {
                IdentityEncoding.field(out,component.getName()); IdentityEncoding.field(out,properties.get(component.getName()));
                describe(componentType(type,component),seen,out);
            }
        }
    }
    public CodecIdentity identity() { return new CodecIdentity(CODEC, VERSION, CONFIGURATION); }
    public void applicationType(Type type) {
        if (type == null || type instanceof WorkflowModel.ProductType)
            throw new IllegalArgumentException("concrete application type required");
        requireType(type);
    }
    void internalType(Type type) {
        if (type instanceof WorkflowModel.ProductType product) product.roles().forEach(this::internalType);
        else applicationType(type);
    }
    /** Binding compatibility deliberately preserves concrete generic contracts. */
    public boolean compatible(Type wanted, Type supplied) { return wanted.equals(supplied); }

    /** Encodes a value using an already concrete, compiler-selected declaration. */
    public byte[] encodeBytes(Object value, Type declaration) {
        return encode(value, reference(declaration)).bytes();
    }

    /** Decodes bytes after their persisted type, shape and codec identity have been verified. */
    public Object decodeBytes(byte[] bytes, Type declaration) {
        String canonical=mapper.constructType(declaration).toCanonical();
        return decode(new Payload(canonical,CODEC,VERSION,CONFIGURATION,bytes),reference(declaration));
    }

    private static TypeReference<Object> reference(Type declaration) {
        return new TypeReference<>() { @Override public Type getType() { return declaration; } };
    }


	static final String CODEC = "jackson-record";
	static final String VERSION = "2.22.2/r1-1";
	static final String CONFIGURATION = "strict-record-and-native-judge-0.18-result-json-v6-duplicate-refusal";

	private final ObjectMapper mapper = JsonMapper.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
		.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).addModule(nativeModule()).build();

    private static com.fasterxml.jackson.databind.Module nativeModule() {
        try {
            Class.forName("io.github.markpollack.judge.verdict.Verdict",false,TypeContracts.class.getClassLoader());
            Class.forName("io.github.markpollack.judge.serialization.VerdictCodec",false,TypeContracts.class.getClassLoader());
        }
        catch(ClassNotFoundException absent) { return new com.fasterxml.jackson.databind.module.SimpleModule(); }
        return NativeVerdictCodec.module();
    }

	/** Checks known type and wire-shape requirements without constructing or encoding values. */
	public void requireType(Type declaration) {
		Type owned=DefinitionOwnership.ownType(declaration);
		requireConcreteDeclaration(owned);
		requireSupported(mapper.constructType(owned), new HashSet<>());
	}

	private static void requireConcreteDeclaration(Type declaration) {
		if (declaration instanceof ParameterizedType parameterized) {
			for (Type argument : parameterized.getActualTypeArguments()) {
				requireConcreteDeclaration(argument);
			}
		}
		else if (declaration instanceof Class<?> raw && raw.getTypeParameters().length > 0) {
			throw new IllegalArgumentException("unsupported declared type " + raw.getTypeName() + ": raw generic declaration");
		}
		else if (!(declaration instanceof Class<?>)) {
			throw new IllegalArgumentException("unsupported unresolved declared type " + declaration);
		}
	}

	public record Payload(String declaredType, String codec, String version, String configuration, byte[] bytes) {
		public Payload {
			bytes = bytes.clone();
		}

		@Override
		public byte[] bytes() {
			return bytes.clone();
		}
	}

	public Payload encode(Object value, TypeReference<?> declaration) {
		Type owned=DefinitionOwnership.ownType(declaration.getType());
		JavaType type = mapper.constructType(owned);
		try {
			requireType(owned);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("encode failed for " + type.toCanonical() + ": " + ex.getMessage(), ex);
		}
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

	public <T> T decode(Payload payload, TypeReference<T> declaration) {
		Type owned=DefinitionOwnership.ownType(declaration.getType());
		JavaType type = mapper.constructType(owned);
		requireType(owned);
		requireIdentity("declared type", type.toCanonical(), payload.declaredType());
		requireIdentity("codec", CODEC, payload.codec());
		requireIdentity("codec version", VERSION, payload.version());
		requireIdentity("codec configuration", CONFIGURATION, payload.configuration());
		try {
			requireShape(mapper.readTree(payload.bytes()), type, false);
			T value = mapper.readValue(payload.bytes(), type);
			requireValue(value, type);
			if (!mapper.readTree(payload.bytes()).equals(mapper.readTree(mapper.writerFor(type).writeValueAsBytes(value)))) {
				throw new IllegalArgumentException("lossless decoding refused: reconstructed wire value differs");
			}
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
			Map<String, String> properties = recordProperties(type);
			for (RecordComponent component : raw.getRecordComponents()) {
				requireShape(node.get(properties.get(component.getName())), componentType(type, component),
						false);
			}
		}
	}

	private void requireSupported(JavaType type, Set<JavaType> seen) {
		if (!seen.add(type)) {
			return;
		}
		Class<?> raw = type.getRawClass();
		if (nativeEvidence(raw)) {
			return;
		}
		if (raw.getTypeParameters().length != type.containedTypeCount()) {
			throw new IllegalArgumentException("unsupported declared type " + type.toCanonical() + ": raw generic declaration");
		}
		if (scalar(raw)) {
			if (raw.isEnum()) {
				requireOrdinaryJacksonAnnotations(raw);
				requireUniqueEnumWireNames(raw);
				BeanDescription description = mapper.getSerializationConfig().introspect(type);
				if (description.findJsonValueAccessor() != null) {
					throw new IllegalArgumentException("unsupported transformed enum shape " + type.toCanonical());
				}
			}
			return;
		}
		if (raw == List.class && type.containedTypeCount() == 1) {
			requireSupported(type.containedType(0), seen);
			return;
		}
		if (raw.isRecord()) {
			recordProperties(type);
			for (RecordComponent component : raw.getRecordComponents()) {
				if (component.getGenericType() instanceof ParameterizedType) {
					rejectWildcardComponents(component.getGenericType());
				}
				requireSupported(componentType(type, component), seen);
			}
			return;
		}
		throw new IllegalArgumentException("unsupported declared type " + type.toCanonical());
	}

	private static void requireUniqueEnumWireNames(Class<?> raw) {
		Set<String> wireNames = new HashSet<>();
		for (var field : raw.getDeclaredFields()) {
			if (!field.isEnumConstant()) continue;
			JsonProperty property = field.getAnnotation(JsonProperty.class);
			String wireName = property == null || property.value().isEmpty() ? field.getName() : property.value();
			if (!wireNames.add(wireName)) {
				throw new IllegalArgumentException("unsupported duplicate enum wire name " + wireName + " in " + raw.getTypeName());
			}
		}
	}

	private static void rejectWildcardComponents(Type declared) {
		if (declared instanceof java.lang.reflect.WildcardType) {
			throw new IllegalArgumentException("unsupported wildcard component " + declared.getTypeName());
		}
		if (declared instanceof ParameterizedType parameterized) {
			for (Type argument : parameterized.getActualTypeArguments()) {
				rejectWildcardComponents(argument);
			}
		}
	}

	private Map<String, String> recordProperties(JavaType type) {
		Class<?> raw = type.getRawClass();
		requireOrdinaryJacksonAnnotations(raw);
		BeanDescription serialization = mapper.getSerializationConfig().introspect(type);
		BeanDescription deserialization = mapper.getDeserializationConfig().introspect(type);
		if (serialization.findJsonValueAccessor() != null || serialization.findAnyGetter() != null
				|| deserialization.findAnySetterAccessor() != null || serialization.getObjectIdInfo() != null) {
			throw new IllegalArgumentException("unsupported transformed record shape " + type.toCanonical());
		}
		Map<String, String> writers = new LinkedHashMap<>();
		Map<String, String> readers = new LinkedHashMap<>();
		serialization.findProperties().forEach(property -> {
			if (property.getPrimaryMember() != null) {
				property.getPrimaryMember().annotations().forEach(annotation -> requireAllowedAnnotation(annotation, false, raw));
			}
			if (property.couldSerialize()) writers.put(property.getInternalName(), property.getName());
		});
		deserialization.findProperties().forEach(property -> {
			if (property.getPrimaryMember() != null) {
				property.getPrimaryMember().annotations().forEach(annotation -> requireAllowedAnnotation(annotation, false, raw));
			}
			if (property.couldDeserialize()) readers.put(property.getInternalName(), property.getName());
		});
		if (!writers.equals(readers) || writers.size() != raw.getRecordComponents().length
				|| new HashSet<>(writers.values()).size() != writers.size()) {
			throw new IllegalArgumentException("unsupported asymmetric record properties " + type.toCanonical());
		}
		for (RecordComponent component : raw.getRecordComponents()) {
			if (!writers.containsKey(component.getName())) {
				throw new IllegalArgumentException("unsupported record property " + type.toCanonical() + "." + component.getName());
			}
		}
		return readers;
	}

	private static void requireOrdinaryJacksonAnnotations(Class<?> raw) {
		requireOrdinaryJacksonAnnotations(raw, new HashSet<>());
	}

	private static void requireOrdinaryJacksonAnnotations(Class<?> raw, Set<Class<?>> seen) {
		if (!seen.add(raw)) return;
		requireAllowedAnnotations(raw, raw);
		for (Class<?> contract : raw.getInterfaces()) requireOrdinaryJacksonAnnotations(contract, seen);
		for (var field : raw.getDeclaredFields()) requireAllowedAnnotations(field, raw);
		for (var method : raw.getDeclaredMethods()) requireAllowedAnnotations(method, raw);
		for (var constructor : raw.getDeclaredConstructors()) {
			requireAllowedAnnotations(constructor, raw);
			for (var parameter : constructor.getParameters()) requireAllowedAnnotations(parameter, raw);
		}
		if (raw.isRecord()) {
			for (var component : raw.getRecordComponents()) requireAllowedAnnotations(component, raw);
		}
	}

	private static void requireAllowedAnnotations(AnnotatedElement element, Class<?> owner) {
		for (Annotation annotation : element.getDeclaredAnnotations()) {
			requireAllowedAnnotation(annotation, element == owner, owner);
		}
	}

	private static void requireAllowedAnnotation(Annotation annotation, boolean classElement, Class<?> owner) {
			Class<? extends Annotation> kind = annotation.annotationType();
			if (annotation instanceof JsonProperty property && property.access() == JsonProperty.Access.AUTO) return;
			if (annotation instanceof JsonPropertyOrder && classElement) return;
			if (annotation instanceof JsonFormat format && classElement && owner.isRecord()) {
				if (format.shape() != JsonFormat.Shape.ANY && format.shape() != JsonFormat.Shape.OBJECT) {
					throw new IllegalArgumentException("unsupported record shape " + owner.getTypeName(),
							new IllegalArgumentException("expected JSON object"));
				}
				if (format.pattern().isEmpty() && format.with().length == 0 && format.without().length == 0
						&& format.locale().equals("##default") && format.timezone().equals("##default")) return;
			}
			if (kind.getName().startsWith("com.fasterxml.jackson.")
					|| kind.isAnnotationPresent(JacksonAnnotationsInside.class)) {
				throw new IllegalArgumentException("unsupported Jackson annotation " + kind.getSimpleName()
						+ " on " + owner.getTypeName());
			}
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
							false);
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
		String name=raw.getName();
        if(!name.equals("io.github.markpollack.judge.verdict.Verdict")) return false;
        try { return raw==Class.forName(name,false,TypeContracts.class.getClassLoader()); }
        catch(ClassNotFoundException missing) { throw new IllegalArgumentException("native evidence codec dependency unavailable",missing); }
	}

	private static boolean scalar(Class<?> raw) {
		return raw == String.class || raw == int.class || raw == Integer.class || raw == long.class
				|| raw == Long.class || raw == boolean.class || raw == Boolean.class || raw == double.class
				|| raw == Double.class || raw == float.class || raw == Float.class || raw.isEnum();
	}

}
