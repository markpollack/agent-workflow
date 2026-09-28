package io.github.markpollack.workflow.batch.durable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import io.github.markpollack.workflow.flows.Step;
import io.github.markpollack.workflow.flows.StepContext;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.workflow.flows.compiler.TypeContracts;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;

/** Contract checks and direct invocation of application-supplied step instances. */
final class ResolvedApplication {
    private final TypeContracts codec = new TypeContracts();
    private final Map<String, Step<?, ?>> operations = new HashMap<>();

    ResolvedApplication(ApplicationDeployment deployment, ValidatedWorkflow workflow) {
        if (!codec.identity().equals(workflow.codecIdentity())
                || !codec.identity().equals(deployment.manifest().codec())) {
            throw new WorkflowRefusal("CODEC_CHANGED", "deployed codec contract differs");
        }
        try {
            for (var value : workflow.values().values()) {
                if (!codec.contract(value.declaration()).equals(value.contract())) {
                    throw new WorkflowRefusal("TYPE_CHANGED", "declared type/shape/codec differs: " + value.identity());
                }
            }
            for (var call : workflow.invocations()) {
                var selected = call.executable();
                if (!deployment.manifest().deploymentDigest().equals(selected.deploymentManifestDigest())
                        || !deployment.manifest().configurationDigest().equals(selected.configurationDigest())) {
                    throw new WorkflowRefusal("EXECUTABLE_CHANGED", "selected deployment/configuration differs at " + call.placement());
                }
                Step<?, ?> step = deployment.step(selected.entryPoint());
                StepTypes actual = StepTypes.of(step.getClass());
                if (!actual.input().equals(selected.input()) || !actual.output().equals(selected.output())) {
                    throw new WorkflowRefusal("STEP_CONTRACT", "supplied Step contract differs: " + selected.entryPoint());
                }
                operations.put(selected.entryPoint(), step);
            }
        } catch (LinkageError ex) {
            throw new WorkflowRefusal("STEP_UNAVAILABLE", "deployed operation/type resolution failed", ex);
        } catch (IllegalArgumentException ex) {
            throw new WorkflowRefusal("TYPE_CHANGED", "deployed type contract refused", ex);
        }
    }

    Object decode(byte[] bytes, Type declaration) {
        try { return codec.decodeBytes(bytes, declaration); }
        catch (IllegalArgumentException | LinkageError ex) {
            throw new WorkflowRefusal("DECODE_FAILED", "saved value cannot decode losslessly", ex);
        }
    }

    byte[] encode(Object value, Type declaration) {
        try { return codec.encodeBytes(value, declaration); }
        catch (IllegalArgumentException | LinkageError ex) {
            throw new WorkflowRefusal("ENCODE_FAILED", "value cannot encode losslessly", ex);
        }
    }

    Object assemble(Type declaration, List<Object> components) {
        Class<?> raw = declaration instanceof Class<?> c ? c : (Class<?>) ((ParameterizedType) declaration).getRawType();
        if (!raw.isRecord()) throw new WorkflowRefusal("BINDING_INVALID", "assembly requires a declared record");
        try {
            Class<?>[] parameters = Arrays.stream(raw.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new);
            return raw.getConstructor(parameters).newInstance(components.toArray());
        } catch (ReflectiveOperationException ex) {
            throw new WorkflowRefusal("INPUT_FAILED", "record input construction failed", unwrap(ex));
        }
    }

    @SuppressWarnings("unchecked") // Concrete generic declarations were checked against compiler selections above.
    Object execute(String entry, Object input, StepContext context) throws Exception {
        return ((Step<Object, Object>) operations.get(entry)).execute(context, input);
    }

    private static Throwable unwrap(Throwable ex) {
        return ex instanceof InvocationTargetException call && call.getCause() != null ? call.getCause() : ex;
    }
}
