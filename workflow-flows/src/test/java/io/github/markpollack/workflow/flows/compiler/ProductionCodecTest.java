package io.github.markpollack.workflow.flows.compiler;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;

import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.verdict.Verdict.Conclusion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionCodecTest {

	private final TypeContracts codec = new TypeContracts();
	private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();
	private static final AtomicInteger ACCESSORS = new AtomicInteger();
	private static final AtomicInteger CUSTOMIZATIONS = new AtomicInteger();

	record Empty() { }
	record Bounded<T extends Item>(T item) { }
	record RawNested(Bounded raw) { }
	enum DuplicateNames { @JsonProperty("same") FIRST, @JsonProperty("same") SECOND }
	enum RenamedChoice { @JsonProperty("one") FIRST, SECOND }
	@JsonFormat(shape = JsonFormat.Shape.NUMBER)
	enum NumericChoice { FIRST, SECOND }
	@Retention(RetentionPolicy.RUNTIME)
	@JacksonAnnotationsInside
	@JsonFormat(shape = JsonFormat.Shape.ARRAY)
	@interface ArrayBundle { }
	@ArrayBundle
	record Bundled(String name) { }

	record Item(@JsonProperty("wire_name") String name) { }
	record Generic<T>(@JsonProperty("wire_items") List<T> items) { }
	record Nested(Generic<Item> generic, List<List<Item>> batches) { }
	record NativeInput(Item subject, Verdict verdict, Conclusion interpretation) { }
	record Callback(Runnable action) { }
	record Ignored(@JsonIgnore String name) { }
	record ReadOnly(@JsonProperty(access = JsonProperty.Access.READ_ONLY) String name) { }
	record Flattened(@JsonUnwrapped Item item) { }
	record Wildcard(List<? extends Item> values) { }

	@com.fasterxml.jackson.databind.annotation.JsonSerialize(using = CustomSerializer.class)
	interface CustomShape { }
	record Customized(String name) implements CustomShape { }

	public static class CustomSerializer extends com.fasterxml.jackson.databind.JsonSerializer<Customized> {
		public CustomSerializer() { CUSTOMIZATIONS.incrementAndGet(); }
		@Override
		public void serialize(Customized value, com.fasterxml.jackson.core.JsonGenerator output,
				com.fasterxml.jackson.databind.SerializerProvider provider) {
			CUSTOMIZATIONS.incrementAndGet();
		}
	}

	@JsonFormat(shape = JsonFormat.Shape.ARRAY)
	record ArrayRecord(String name) { }

	record ScalarRecord(String name) {
		@JsonValue
		public String asString() { return name; }
	}

	record Observable(String name) {
		Observable { CONSTRUCTIONS.incrementAndGet(); }
		@Override public String name() { ACCESSORS.incrementAndGet(); return name; }
	}

	@Test
	void feasibilityAcceptsConcreteNestedGenericAndRenamedPropertyContracts() {
		assertThatCode(() -> codec.requireType(Empty.class)).doesNotThrowAnyException();
		assertThatCode(() -> codec.requireType(Boolean.class)).doesNotThrowAnyException();
		assertThatCode(() -> codec.requireType(Item.class)).doesNotThrowAnyException();
		assertThatCode(() -> codec.requireType(Nested.class)).doesNotThrowAnyException();
		var type = new TypeReference<Generic<Item>>() { };
		assertThatCode(() -> codec.requireType(type.getType())).doesNotThrowAnyException();
		var value = new Generic<>(List.of(new Item("exact")));
		assertThat(codec.decode(codec.encode(value, type), type)).isEqualTo(value);
	}

	@Test
	void feasibilityHonorsNativeEvidenceDomainWithoutRecursingIntoItsPortableObjectMetadata() {
		assertThatCode(() -> codec.requireType(Verdict.class)).doesNotThrowAnyException();
		assertThatCode(() -> codec.requireType(Conclusion.class)).doesNotThrowAnyException();
		assertThatCode(() -> codec.requireType(NativeInput.class)).doesNotThrowAnyException();
		assertThatCode(() -> codec.requireType(Conclusion.class)).doesNotThrowAnyException();
		assertThatThrownBy(() -> codec.requireType(Object.class)).hasMessageContaining("unsupported declared type");
		assertThatThrownBy(() -> codec.requireType(Callback.class)).hasMessageContaining("java.lang.Runnable");
	}

	@Test
	void knownShapeTransformsRefuseAtTypeCheckBeforeAnyValueExists() {
		for (Class<?> type : List.of(ArrayRecord.class, ScalarRecord.class, Ignored.class, ReadOnly.class, Flattened.class, NumericChoice.class, Bundled.class)) {
			assertThatThrownBy(() -> codec.requireType(type)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unsupported");
		}
		assertThatThrownBy(() -> codec.encode(new ArrayRecord("x"), new TypeReference<ArrayRecord>() { }))
			.hasMessageContaining("encode failed");
	}

	@Test
	void rawWildcardAndUnresolvedContractsCannotGainFidelityFromJacksonErasure() {
		assertThatThrownBy(() -> codec.requireType(List.class)).hasMessageContaining("unsupported declared type");
		assertThatThrownBy(() -> codec.requireType(Generic.class)).hasMessageContaining("raw generic");
		assertThatThrownBy(() -> codec.requireType(new TypeReference<List<Object>>() { }.getType()))
			.hasMessageContaining("java.lang.Object");
		assertThatThrownBy(() -> codec.requireType(new TypeReference<List<? extends Item>>() { }.getType()))
			.hasMessageContaining("unresolved");
		assertThatThrownBy(() -> codec.requireType(Wildcard.class)).hasMessageContaining("wildcard");
	}

	@Test
	void boundedRawGenericsAndDuplicateEnumWireNamesRefuseBeforeEncoding() {
		assertThatThrownBy(() -> codec.requireType(Bounded.class)).hasMessageContaining("raw generic");
		assertThatThrownBy(() -> codec.requireType(RawNested.class)).hasMessageContaining("raw generic");
		assertThatCode(() -> codec.requireType(new TypeReference<Bounded<Item>>() { }.getType())).doesNotThrowAnyException();
		assertThatThrownBy(() -> codec.requireType(DuplicateNames.class)).hasMessageContaining("duplicate enum wire name");
		var type = new TypeReference<RenamedChoice>() { };
		for (RenamedChoice choice : RenamedChoice.values()) {
			assertThat(codec.decode(codec.encode(choice, type), type)).isEqualTo(choice);
		}
	}

	@Test
	void feasibilityInvokesNeitherConstructorsNorAccessors() {
		CONSTRUCTIONS.set(0);
		ACCESSORS.set(0);
		codec.requireType(Observable.class);
		assertThat(CONSTRUCTIONS).hasValue(0);
		assertThat(ACCESSORS).hasValue(0);
		CUSTOMIZATIONS.set(0);
		assertThatThrownBy(() -> codec.requireType(Customized.class)).hasMessageContaining("JsonSerialize");
		assertThat(CUSTOMIZATIONS).hasValue(0);
	}

}
