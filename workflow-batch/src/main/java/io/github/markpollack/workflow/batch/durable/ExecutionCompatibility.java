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
	 * Inspectable deployment attestation and observed metadata, not an executable
	 * archive.
	 *
	 * @param applicationId application namespace
	 * @param buildId immutable application/dependency build identifier
	 * @param configurationDigest digest of the exact configuration supplied to operations
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

	private final Map<String, String> configuration;

	/**
	 * Record declared settings and observe codec/runtime compatibility. This is not an
	 * application settings service; supply the same settings separately to Step
	 * constructors.
	 * @param applicationId application namespace
	 * @param buildId immutable code/dependency build identity
	 * @param configuration copied declared configuration used for compatibility
	 */
	public ExecutionCompatibility(String applicationId, String buildId, Map<String, String> configuration) {
		this.configuration = Map.copyOf(configuration);
		this.configuration.forEach((key, value) -> {
			requireUnicode(key);
			requireUnicode(value);
		});

		String[] fields = new TreeMap<>(this.configuration).entrySet()
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

	Map<String, String> configuration() {
		return configuration;
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
