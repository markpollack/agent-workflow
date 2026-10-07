package io.github.markpollack.workflow.flows.compiler;

import java.io.IOException;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.markpollack.judge.verdict.Verdict;
import io.github.markpollack.judge.serialization.VerdictCodec;

/** Embeds the producer's complete strict native document in application records. */
final class NativeVerdictCodec {

	private NativeVerdictCodec() {
	}

	private static final String PRODUCER = artifact(Verdict.class) + ":" + artifact(VerdictCodec.class);

	static String identity() {
		return PRODUCER;
	}

	private static String artifact(Class<?> type) {
		try {
			var location = java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
			var digest = java.security.MessageDigest.getInstance("SHA-256");
			if (java.nio.file.Files.isRegularFile(location)) {
				try (var input = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(location),
						digest)) {
					input.transferTo(java.io.OutputStream.nullOutputStream());
				}
			}
			else {
				try (var files = java.nio.file.Files.walk(location)) {
					for (var file : files.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
						digest.update(
								location.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
						digest.update(java.nio.file.Files.readAllBytes(file));
					}
				}
			}
			return java.util.HexFormat.of().formatHex(digest.digest());
		}
		catch (Exception failure) {
			throw new IllegalArgumentException("native producer artifact cannot be identified", failure);
		}
	}

	static com.fasterxml.jackson.databind.Module module() {
		var codec = new VerdictCodec();
		var module = new SimpleModule("workflow-native-verdict");
		module.addSerializer(Verdict.class, new JsonSerializer<>() {
			public void serialize(Verdict value, JsonGenerator output, SerializerProvider provider) throws IOException {
				output.writeRawValue(codec.write(value));
			}
		});
		module.addDeserializer(Verdict.class, new JsonDeserializer<>() {
			public Verdict deserialize(JsonParser input, DeserializationContext context) throws IOException {
				return codec.read(context.readTree(input).toString());
			}
		});
		return module;
	}

}
