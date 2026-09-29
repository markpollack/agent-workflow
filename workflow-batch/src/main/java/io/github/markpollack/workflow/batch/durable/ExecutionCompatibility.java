package io.github.markpollack.workflow.batch.durable;

import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.flows.compiler.TypeContracts;

/**
 * Copied application build, settings and observed codec/runtime facts for admission and
 * recovery.
 */
public final class ExecutionCompatibility {

	/**
	 * Inspectable declared compatibility and observed metadata. These facts do not
	 * attest to every supplied object's internal state or archive executable bytes.
	 *
	 * @param applicationId application namespace
	 * @param buildId immutable application/dependency build identifier
	 * @param configurationDigest digest of the declared settings used for compatibility checks
	 * @param codec fixed value codec contract
	 * @param jacksonCoreVersion observed Jackson core version
	 * @param jacksonDatabindVersion observed Jackson databind version
	 * @param javaRuntimeVersion observed Java runtime version
	 * @param javaVendor observed Java vendor
	 * @param javaVmName observed VM name
	 */
	public record Manifest(String applicationId, String buildId, String configurationDigest,
			TypeContracts.CodecIdentity codec, String jacksonCoreVersion, String jacksonDatabindVersion,
			String javaRuntimeVersion, String javaVendor, String javaVmName) {
		public Manifest {
			requireText(applicationId, "application ID");
			requireText(buildId, "build ID");
			requireText(configurationDigest, "configuration digest");
			java.util.Objects.requireNonNull(codec, "codec");
			requireText(jacksonCoreVersion, "Jackson core version");
			requireText(jacksonDatabindVersion, "Jackson databind version");
			requireText(javaRuntimeVersion, "Java runtime version");
			requireText(javaVendor, "Java vendor");
			requireText(javaVmName, "Java VM name");
		}

		/**
		 * Fingerprint of declared application/build metadata; it does not measure code
		 * bytes.
		 */
		public String deploymentDigest() {
			return Digests.fields("application-deployment-v1", applicationId, buildId);
		}

		String identity() {
			return Digests.fields(deploymentDigest(), configurationDigest, codec.name(), codec.version(),
					codec.configuration(), jacksonCoreVersion, jacksonDatabindVersion, javaRuntimeVersion, javaVendor,
					javaVmName);
		}
	}

	private final Manifest manifest;

	/**
	 * Record declared settings and observe codec/runtime compatibility. This is not an
	 * application settings service; supply the same settings separately to Step
	 * constructors.
	 * @param applicationId application namespace
	 * @param buildId immutable code/dependency build identity
	 * @param configuration copied declared configuration used for compatibility
	 */
	public ExecutionCompatibility(String applicationId, String buildId, Map<String, String> configuration) {
		Map<String, String> settings = Map.copyOf(configuration);
		settings.forEach((key, value) -> {
			requireUnicode(key);
			requireUnicode(value);
		});

		String[] fields = new TreeMap<>(settings).entrySet()
			.stream()
			.flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue()))
			.toArray(String[]::new);
		manifest = new Manifest(applicationId, buildId, Digests.fields(fields), new TypeContracts().identity(),
				new JsonFactory().version().toString(), new ObjectMapper().version().toString(),
				System.getProperty("java.runtime.version"), System.getProperty("java.vendor"),
				System.getProperty("java.vm.name"));
	}

	/**
	 * Returns immutable declaration/configuration/codec/runtime metadata for inspection.
	 */
	public Manifest manifest() {
		return manifest;
	}

	private static void requireUnicode(String text) {
		if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text))
			throw new IllegalArgumentException("deployment identity/configuration must contain well-formed Unicode");
	}

	private static void requireText(String text, String field) {
		if (text == null || text.isBlank())
			throw new IllegalArgumentException(field + " required");
		requireUnicode(text);
	}

}
