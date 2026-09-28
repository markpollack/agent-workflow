package io.github.markpollack.workflow.batch.durable;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.markpollack.workflow.flows.Step;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.flows.compiler.ExecutableIdentity;
import io.github.markpollack.workflow.flows.compiler.TypeContracts;

/**
 * Immutable registration of the application-supplied steps and configuration used by a
 * runtime handle. The application supplies a distinct immutable build ID when code or
 * dependencies change. Identity comparison is not verification of executable bytes or
 * external dependencies.
 * <p>
 * The registration boundary is a map of stable names to exact Step instances. Construct
 * them with dependencies or obtain them from the application's container. This class
 * copies registration/configuration, not the dependency graph, and never constructs or
 * closes a supplied object. SequentialWorkflow uses these registrations to build a
 * definition; ResolvedApplication checks them again for admission and execution. The same
 * supplied instance can serve concurrent runs, so its thread safety is the application's
 * responsibility. Registration names select objects; map iteration order never determines
 * workflow execution order. A supplied object's Java identity is not persisted, so a new
 * compatible application process supplies new instances under the same stable names.
 */
public final class ApplicationDeployment {

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

	private final Map<String, Step<?, ?>> steps;

	/**
	 * Register named application-supplied steps. The application owns constructor
	 * injection and dependency lifecycle; this runtime neither constructs nor closes
	 * those objects. Multiple runs may invoke a registered step concurrently.
	 * @param applicationId application namespace
	 * @param buildId immutable code/dependency build identity
	 * @param configuration copied declared configuration used for compatibility
	 * @param steps stable registration IDs and their supplied instances
	 */
	public ApplicationDeployment(String applicationId, String buildId, Map<String, String> configuration,
			Map<String, ? extends Step<?, ?>> steps) {
		this.configuration = Map.copyOf(configuration);
		this.configuration.forEach((key, value) -> {
			requireUnicode(key);
			requireUnicode(value);
		});
		steps.keySet().forEach(id -> requireText(id, "step registration ID"));
		this.steps = Map.copyOf(steps);
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

	/**
	 * Read the registered instance's concrete Step signature and select its full types
	 * together with this deployment/configuration identity. Does not invoke the step.
	 * @param id fixed registration name used in a workflow placement
	 * @return immutable executable selection for definition validation
	 * @throws WorkflowRefusal if registration is missing or its declaration is unresolved
	 */
	public ExecutableIdentity selection(String id) {
		StepTypes types = StepTypes.of(step(id).getClass());
		return selection(id, types.input(), types.output());
	}

	/**
	 * Select explicit contracts for lower-level definition construction. These are not
	 * trusted object hints: admission verifies them against the supplied Step signature.
	 * Ordinary authors use selection(id) indirectly through define/then/build.
	 * @param id existing registration name
	 * @param input declared full input Type
	 * @param output declared full output Type
	 * @return immutable selection; no workflow is admitted or step invoked
	 */
	public ExecutableIdentity selection(String id, Type input, Type output) {
		step(id);
		return new ExecutableIdentity(id, manifest.deploymentDigest(), manifest.configurationDigest(), input, output);
	}

	/**
	 * Begin a mutable sequential definition builder bound to these registrations. This
	 * convenience entry uses the deployment to discover full Step types and record
	 * executable selections; it is not a workflow-definition registry. The builder's
	 * build() validates structure, full input bindings and durable shapes and returns an
	 * immutable ValidatedWorkflow. This is workflow-definition validation, not javac type
	 * checking; no run is created and no Step executes during building.
	 * @param name stable authored workflow name, included in compatibility identity
	 * @return an application-local builder; do not share it concurrently
	 */
	public SequentialWorkflow define(String name) {
		return new SequentialWorkflow(this, name);
	}

	Map<String, String> configuration() {
		return configuration;
	}

	Step<?, ?> step(String id) {
		Step<?, ?> step = steps.get(id);
		if (step == null)
			throw new WorkflowRefusal("STEP_UNAVAILABLE", "step is not registered: " + id);
		return step;
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
