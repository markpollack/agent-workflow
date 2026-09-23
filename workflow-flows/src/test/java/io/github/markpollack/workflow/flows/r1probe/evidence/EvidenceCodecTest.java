package io.github.markpollack.workflow.flows.r1probe.evidence;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.markpollack.judge.jury.Verdict;
import io.github.markpollack.judge.Judges;
import io.github.markpollack.judge.jury.SimpleJury;
import io.github.markpollack.judge.jury.ConsensusStrategy;
import io.github.markpollack.judge.jury.interpretation.VerdictReading;
import io.github.markpollack.judge.jury.interpretation.Verdicts;
import io.github.markpollack.judge.result.Judgment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceCodecTest {

	private final PinnedRecordCodec codec = new PinnedRecordCodec();

	record Line(String label, List<String> references) { }

	record EffectiveInput(String request, List<Line> lines, List<List<Line>> batches) { }

	record Box<T>(List<T> values) { }

	record Count(int count) { }

	record NumericInput(long sequence, float fraction, List<Long> references) { }

	record Renamed(@com.fasterxml.jackson.annotation.JsonProperty("wire_name") String name) { }

	record RenamedInput(@com.fasterxml.jackson.annotation.JsonProperty("wire_values") List<Renamed> values) { }

	@com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.ARRAY)
	record ArrayShape(String name) { }

	record Unsupported(Runnable callback) { }

	record Broken(String text) {
		@Override
		public String text() {
			throw new IllegalStateException("cannot read payload");
		}
	}

	@Test
	void nestedRecordsGenericListsAndEffectiveInputHaveIndependentSnapshots() {
		List<String> references = new ArrayList<>(List.of("revision-a"));
		List<Line> lines = new ArrayList<>(List.of(new Line("initial", references)));
		EffectiveInput original = new EffectiveInput("request", lines, List.of(lines));
		var type = new TypeReference<EffectiveInput>() { };
		var encoded = codec.encode(original, type);
		references.add("changed-after-encoding");
		lines.clear();
		EffectiveInput recovered = codec.decode(encoded, type);
		EffectiveInput expected = new EffectiveInput("request", List.of(new Line("initial", List.of("revision-a"))),
				List.of(List.of(new Line("initial", List.of("revision-a")))));
		assertThat(recovered).isEqualTo(expected);
		assertThat(recovered.lines().getFirst()).isInstanceOf(Line.class);
		recovered.lines().getFirst().references().add("consumer-change");
		assertThat(codec.decode(encoded, type)).isEqualTo(expected);
		byte[] exposedBytes = encoded.bytes();
		exposedBytes[0] = 0;
		assertThat(codec.decode(encoded, type)).isEqualTo(expected);
		assertThat(java.io.Serializable.class.isAssignableFrom(EffectiveInput.class)).isFalse();
	}

	@Test
	void genericRecordTypeParametersRetainElementTypes() {
		var type = new TypeReference<Box<Line>>() { };
		Box<Line> original = new Box<>(List.of(new Line("typed", List.of("a"))));
		Box<Line> recovered = codec.decode(codec.encode(original, type), type);
		assertThat(recovered).isEqualTo(original);
		assertThat(recovered.values().getFirst()).isInstanceOf(Line.class);
	}

	@ParameterizedTest
	@EnumSource(VerdictReading.class)
	void fullNativeEvidenceRoundTripsForEverySupportedReading(VerdictReading reading) {
		var original = NativeJudgeProbe.assess(NativeJudgeProbe.jury(reading), NativeJudgeProbe.CONTEXT);
		var type = new TypeReference<NativeJudgeProbe.Assessment>() { };
		var decoded = codec.decode(codec.encode(original, type), type);
		assertThat(decoded).isEqualTo(original);
		assertThat(decoded.verdict()).isNotSameAs(original.verdict());
		assertThat(decoded.verdict().individualByName()).isEqualTo(original.verdict().individualByName());
		assertThat(decoded.verdict().aggregated().metadata()).isEqualTo(original.verdict().aggregated().metadata());
		assertThat(decoded.interpretation()).isEqualTo(Verdicts.interpret(decoded.verdict()));
	}

	@Test
	void nativeCompositeAttemptsFailuresChecksAndProvenanceAreNotFlattened() {
		var original = NativeJudgeProbe.assess(NativeJudgeProbe.composite(), NativeJudgeProbe.CONTEXT);
		assertThat(original.verdict().compositeAttempts()).hasSize(2);
		assertThat(original.interpretation().stages()).hasSize(2);
		var type = new TypeReference<NativeJudgeProbe.Assessment>() { };
		var decoded = codec.decode(codec.encode(original, type), type);
		assertThat(decoded).isEqualTo(original);
		assertThat(decoded.verdict().compositeAttempts().getFirst().verdict()).isNull();
		assertThat(decoded.verdict().compositeAttempts().get(1).verdict().individual().getFirst().checks()).hasSize(1);
		assertThat(decoded.interpretation().decidedBy()).isEqualTo(original.interpretation().decidedBy());
	}

	@Test
	void failedStrictAssessmentKeepsFullAvailableEvidenceAndReason() {
		Verdict verdict = Verdict.single("manual", Judgment.pass("legacy record"));
		var interpretation = Verdicts.interpret(verdict);
		var original = new NativeJudgeProbe.Assessment(verdict, interpretation,
				NativeJudgeProbe.route(interpretation), NativeJudgeProbe.failure(interpretation));
		var type = new TypeReference<NativeJudgeProbe.Assessment>() { };
		var decoded = codec.decode(codec.encode(original, type), type);
		assertThat(decoded).isEqualTo(original);
		assertThat(decoded.route()).isNull();
		assertThat(decoded.failure()).contains("UNDETERMINED");
		assertThat(decoded.interpretation().defects()).isNotEmpty();
		var exception = NativeJudgeProbe.assess(NativeJudgeProbe.throwing(), NativeJudgeProbe.CONTEXT);
		assertThat(codec.decode(codec.encode(exception, type), type)).isEqualTo(exception);
	}

	@Test
	void everyCompatibilityCoordinateMustMatchBeforeDecoding() {
		var type = new TypeReference<List<Line>>() { };
		var encoded = codec.encode(List.of(new Line("x", List.of())), type);
		assertThatThrownBy(() -> codec.decode(encoded, new TypeReference<List<String>>() { }))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("declared type incompatible");
		assertThatThrownBy(() -> codec.decode(new PinnedRecordCodec.Payload(encoded.declaredType(), "other",
				encoded.version(), encoded.configuration(), encoded.bytes()), type)).hasMessageContaining("codec incompatible");
		assertThatThrownBy(() -> codec.decode(new PinnedRecordCodec.Payload(encoded.declaredType(), encoded.codec(),
				"next", encoded.configuration(), encoded.bytes()), type)).hasMessageContaining("codec version incompatible");
		assertThatThrownBy(() -> codec.decode(new PinnedRecordCodec.Payload(encoded.declaredType(), encoded.codec(),
				encoded.version(), "permissive", encoded.bytes()), type)).hasMessageContaining("codec configuration incompatible");
	}

	@Test
	void unsupportedDeclarationsAndWrongRuntimeElementsRefuseInsteadOfStringFallback() {
		assertThatThrownBy(() -> codec.encode(new Unsupported(() -> { }), new TypeReference<Unsupported>() { }))
			.hasMessageContaining("unsupported declared type java.lang.Runnable");
		assertThatThrownBy(() -> codec.encode(List.of("anything"), new TypeReference<List<Object>>() { }))
			.hasMessageContaining("unsupported declared type java.lang.Object");
		assertThatThrownBy(() -> codec.encode(List.of("wrong element"), new TypeReference<List<Line>>() { }))
			.hasMessageContaining("value type mismatch");
		assertThatThrownBy(() -> codec.encode(new Broken("x"), new TypeReference<Broken>() { }))
			.hasMessageContaining("cannot read component text");
		var payload = new PinnedRecordCodec.Payload("java.lang.Runnable", PinnedRecordCodec.CODEC,
				PinnedRecordCodec.VERSION, PinnedRecordCodec.CONFIGURATION, "\"not a callback\"".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> codec.decode(payload, new TypeReference<Runnable>() { }))
			.hasMessageContaining("unsupported declared type java.lang.Runnable");
	}

	@Test
	void corruptPayloadRefusesAndLeavesAuthoritativeBytesAvailable() {
		var type = new TypeReference<Line>() { };
		var encoded = codec.encode(new Line("x", List.of("a")), type);
		byte[] corrupt = "\"a lossy fallback\"".getBytes(StandardCharsets.UTF_8);
		var damaged = new PinnedRecordCodec.Payload(encoded.declaredType(), encoded.codec(), encoded.version(),
				encoded.configuration(), corrupt);
		assertThatThrownBy(() -> codec.decode(damaged, type)).hasMessageContaining("decode failed");
		assertThat(damaged.bytes()).isEqualTo(corrupt);
	}

	@Test
	void applicationNullMissingComponentsAndCoercedScalarsRefuse() {
		var type = new TypeReference<Line>() { };
		assertThatThrownBy(() -> codec.encode(new Line(null, List.of()), type))
			.hasMessageContaining("null value forbidden");
		var encoded = codec.encode(new Line("x", List.of()), type);
		for (String json : List.of("{\"references\":[]}", "{\"label\":null,\"references\":[]}",
				"{\"label\":1,\"references\":[]}", "{\"label\":\"x\",\"references\":[null]}")) {
			var damaged = new PinnedRecordCodec.Payload(encoded.declaredType(), encoded.codec(), encoded.version(),
					encoded.configuration(), json.getBytes(StandardCharsets.UTF_8));
			assertThatThrownBy(() -> codec.decode(damaged, type)).hasMessageContaining("decode failed");
		}
		var countType = new TypeReference<Count>() { };
		var count = codec.encode(new Count(1), countType);
		for (String json : List.of("{\"count\":\"2\"}", "{\"count\":1.5}", "{\"count\":null}", "{}")) {
			var damaged = new PinnedRecordCodec.Payload(count.declaredType(), count.codec(), count.version(),
					count.configuration(), json.getBytes(StandardCharsets.UTF_8));
			assertThatThrownBy(() -> codec.decode(damaged, countType)).hasMessageContaining("decode failed");
		}
	}

	@Test
	void nativeMetadataRejectsRecordsBeforeTheWorkflowCodecRuns() {
		Line record = new Line("typed", List.of("a"));
		assertThatThrownBy(() -> Judgment.pass("ok").toBuilder().metadata("record", record).build())
			.hasMessageContaining("not a portable metadata value type");
		assertThatThrownBy(() -> Judgment.pass("ok").toBuilder().metadata("records", List.of(record)).build())
			.hasMessageContaining("not a portable metadata value type");
	}

	@Test
	void directJacksonNativeMetadataLosesNumericWrapperIdentityCounterexample() throws Exception {
		Judgment judgment = Judgment.pass("numeric evidence").toBuilder()
			.metadata("smallLong", 7L).metadata("fraction", 0.5f).build();
		Verdict original = Verdict.single("numeric", judgment);
		ObjectMapper mapper = new ObjectMapper();
		Verdict decoded = mapper.readValue(mapper.writeValueAsBytes(original), Verdict.class);
		assertThat(original.aggregated().metadata()).containsAllEntriesOf(Map.of("smallLong", 7L, "fraction", 0.5f));
		assertThat(decoded.aggregated().metadata().get("smallLong")).isInstanceOf(Integer.class);
		assertThat(decoded.aggregated().metadata().get("fraction")).isInstanceOf(Double.class);
		assertThat(decoded).isNotEqualTo(original);
		assertThat(Verdicts.interpret(decoded)).isEqualTo(Verdicts.interpret(original));
		var type = new TypeReference<Verdict>() { };
		Verdict recovered = codec.decode(codec.encode(original, type), type);
		assertThat(mapper.readTree(mapper.writeValueAsBytes(recovered)))
			.isEqualTo(mapper.readTree(mapper.writeValueAsBytes(original)));
		assertThat(mapper.valueToTree(recovered).get("aggregated").get("metadata"))
			.isEqualTo(mapper.readTree("{\"smallLong\":7,\"fraction\":0.5}"));
	}

	@Test
	void nativePortableNumericBoundariesPreserveFullWireEvidenceAndInterpretation() throws Exception {
		Judgment judgment = Judgment.pass("portable numeric boundaries").toBuilder()
			.metadata("minimum", -9007199254740991L).metadata("maximum", 9007199254740991L)
			.metadata("floatFraction", 0.1f).metadata("doubleFraction", 0.1234567890123456d).build();
		var jury = SimpleJury.builder().judge(Judges.named(context -> judgment, "numeric"))
			.votingStrategy(new ConsensusStrategy()).parallel(false).build();
		var original = NativeJudgeProbe.assess(jury, NativeJudgeProbe.CONTEXT);
		var type = new TypeReference<NativeJudgeProbe.Assessment>() { };
		var decoded = codec.decode(codec.encode(original, type), type);
		ObjectMapper mapper = new ObjectMapper();
		assertThat(mapper.readTree(mapper.writeValueAsBytes(decoded)))
			.isEqualTo(mapper.readTree(mapper.writeValueAsBytes(original)));
		com.fasterxml.jackson.databind.JsonNode metadata = mapper.valueToTree(decoded.verdict().individual().getFirst().metadata());
		assertThat(metadata)
			.isEqualTo(mapper.readTree("{\"minimum\":-9007199254740991,\"maximum\":9007199254740991,"
					+ "\"floatFraction\":0.1,\"doubleFraction\":0.1234567890123456}"));
		assertThat(decoded.interpretation()).isEqualTo(original.interpretation());
		assertThat(decoded.route()).isEqualTo(VerdictReading.ACCEPTED);
		for (Object forbidden : List.of(9007199254740992L, -9007199254740992L,
				Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
			assertThatThrownBy(() -> Judgment.pass("ok").toBuilder().metadata("forbidden", forbidden).build())
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("metadata.forbidden");
		}
	}

	@Test
	void declaredApplicationLongAndFloatTypesRemainExact() {
		var type = new TypeReference<NumericInput>() { };
		NumericInput original = new NumericInput(Long.MAX_VALUE, 0.1f, List.of(7L, Long.MIN_VALUE));
		NumericInput decoded = codec.decode(codec.encode(original, type), type);
		assertThat(decoded).isEqualTo(original);
		assertThat(decoded.references().getFirst()).isInstanceOf(Long.class);
	}

	@Test
	void jacksonRenamedPropertiesValidateAgainstTheirActualWireNames() throws Exception {
		var type = new TypeReference<RenamedInput>() { };
		var original = new RenamedInput(List.of(new Renamed("name")));
		var payload = codec.encode(original, type);
		assertThat(new ObjectMapper().readTree(payload.bytes()))
			.isEqualTo(new ObjectMapper().readTree("{\"wire_values\":[{\"wire_name\":\"name\"}]}"));
		assertThat(codec.decode(payload, type)).isEqualTo(original);
		var wrong = new PinnedRecordCodec.Payload(payload.declaredType(), payload.codec(), payload.version(),
				payload.configuration(), "{\"wire_values\":[{\"name\":\"name\"}]}".getBytes(StandardCharsets.UTF_8));
		assertThatThrownBy(() -> codec.decode(wrong, type)).hasMessageContaining("decode failed");
	}

	@Test
	void unsupportedJacksonRecordShapeRefusesBeforeReturningEncodedPayload() {
		assertThatThrownBy(() -> codec.encode(new ArrayShape("name"), new TypeReference<ArrayShape>() { }))
			.hasMessageContaining("encode failed").hasRootCauseMessage("expected JSON object");
	}

	@Test
	void globalMissingCreatorPolicyRejectsLegallyAbsentNativeFieldsCounterexample() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		byte[] bytes = mapper.writeValueAsBytes(Verdict.single("one", Judgment.pass("ok")));
		mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
		assertThatThrownBy(() -> mapper.readValue(bytes, Verdict.class)).hasMessageContaining("Missing creator property 'score'");
	}

	@Test
	void disablingScalarCoercionAloneStillCoercesNumberToStringCounterexample() throws Exception {
		var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
			.disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
		assertThat(mapper.readValue("{\"label\":1,\"references\":[]}", Line.class).label()).isEqualTo("1");
	}

}
