package io.github.markpollack.workflow.batch.durable;

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.flows.compiler.ExecutableIdentity;
import io.github.markpollack.workflow.flows.compiler.TypeContracts;

/**
 * Immutable registration of the application classes and configuration used by a runtime handle.
 * The application supplies a distinct immutable build ID when code or dependencies change.
 * Identity comparison is not verification of executable bytes or external dependencies.
 */
public final class ApplicationDeployment {
    /**
     * Inspectable deployment attestation and observed metadata, not an executable archive.
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

        /** Fingerprint of declared application/build metadata; it does not measure code bytes. */
        public String deploymentDigest() {
            return Digests.fields("application-deployment-v1", applicationId, buildId);
        }

        String identity() {
            return Digests.fields(deploymentDigest(), configurationDigest, codec.name(), codec.version(),
                    codec.configuration(), jacksonCoreVersion, jacksonDatabindVersion,
                    javaRuntimeVersion, javaVendor, javaVmName);
        }
    }

    private final Manifest manifest;
    private final Map<String, String> configuration;
    private final Map<String, Class<? extends DurableOperation<?, ?>>> operations;

    /**
     * Snapshot one deployment. Classes are supplied by the application's ordinary class loader.
     * No operation is constructed or invoked here.
     *
     * @param applicationId application namespace
     * @param buildId immutable build identifier, compared by exact equality
     * @param configuration immutable behavior inputs, copied before hashing and execution
     * @param operations explicitly available operation classes; duplicate names refuse
     */
    public ApplicationDeployment(String applicationId, String buildId, Map<String, String> configuration,
            Collection<? extends Class<? extends DurableOperation<?, ?>>> operations) {
        this.configuration = Map.copyOf(configuration);
        this.configuration.forEach((key, value) -> { requireUnicode(key); requireUnicode(value); });
        Map<String, Class<? extends DurableOperation<?, ?>>> registered = new LinkedHashMap<>();
        for (Class<? extends DurableOperation<?, ?>> operation : operations) {
            if (registered.putIfAbsent(operation.getName(), operation) != null) {
                throw new IllegalArgumentException("duplicate operation registration: " + operation.getName());
            }
        }
        this.operations = Map.copyOf(registered);
        String[] fields = new TreeMap<>(this.configuration).entrySet().stream()
                .flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue()))
                .toArray(String[]::new);
        manifest = new Manifest(applicationId, buildId, Digests.fields(fields), new TypeContracts().identity(),
                new JsonFactory().version().toString(), new ObjectMapper().version().toString(),
                System.getProperty("java.runtime.version"), System.getProperty("java.vendor"),
                System.getProperty("java.vm.name"));
    }

    /** Returns immutable declaration/configuration/codec/runtime metadata for inspection. */
    public Manifest manifest() { return manifest; }

    /** Select a registered class and concrete contracts for the validated compiler boundary. */
    public ExecutableIdentity selection(Class<? extends DurableOperation<?, ?>> operation, Type input, Type output) {
        if (operations.get(operation.getName()) != operation) {
            throw new WorkflowRefusal("OPERATION_UNAVAILABLE", "class is not registered: " + operation.getName());
        }
        return new ExecutableIdentity(operation.getName(), manifest.deploymentDigest(),
                manifest.configurationDigest(), input, output);
    }

    Map<String, String> configuration() { return configuration; }
    Class<? extends DurableOperation<?, ?>> operation(String entryPoint) {
        Class<? extends DurableOperation<?, ?>> operation = operations.get(entryPoint);
        if (operation == null) throw new WorkflowRefusal("OPERATION_UNAVAILABLE", "operation is not registered: " + entryPoint);
        return operation;
    }

    private static void requireUnicode(String text) {
        if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException("deployment identity/configuration must contain well-formed Unicode");
    }

    private static void requireText(String text, String field) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(field + " required");
        requireUnicode(text);
    }
}
