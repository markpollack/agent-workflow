package io.github.markpollack.workflow.batch.durable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.markpollack.workflow.flows.compiler.TypeContracts;
import io.github.markpollack.workflow.flows.compiler.ValidatedWorkflow;

/** Direct contract checks and invocation of the handle's registered application classes. */
final class ResolvedApplication {
    private final TypeContracts codec = new TypeContracts();
    private final Map<String, String> configuration;
    private final Map<String, Constructor<?>> operations = new HashMap<>();

    ResolvedApplication(ApplicationDeployment deployment, ValidatedWorkflow workflow) {
        configuration = deployment.configuration();
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
                Class<?> operation = deployment.operation(selected.entryPoint());
                if (!Modifier.isPublic(operation.getModifiers()) || Modifier.isAbstract(operation.getModifiers())) {
                    throw new WorkflowRefusal("EXECUTABLE_CONTRACT", "concrete public operation required");
                }
                Constructor<?> constructor = operation.getConstructor();
                boolean witnessed = false;
                for (Type parent : operation.getGenericInterfaces()) {
                    if (parent instanceof ParameterizedType p && p.getRawType() == DurableOperation.class) {
                        Type[] args = p.getActualTypeArguments();
                        witnessed = args[0].equals(selected.input()) && args[1].equals(selected.output());
                    }
                }
                if (!witnessed) {
                    throw new WorkflowRefusal("EXECUTABLE_CONTRACT", "direct concrete DurableOperation contract required: " + selected.entryPoint());
                }
                operations.put(selected.entryPoint(), constructor);
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new WorkflowRefusal("OPERATION_UNAVAILABLE", "deployed operation/type resolution failed", ex);
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

    @SuppressWarnings("unchecked") // The exact registered class and direct concrete signature were checked above.
    Object execute(String entry, Object input, DeliveryContext context) throws Exception {
        try {
            DurableOperation<Object, Object> operation = (DurableOperation<Object, Object>) operations.get(entry).newInstance();
            return operation.execute(input, context, configuration);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw ex;
        }
    }

    private static Throwable unwrap(Throwable ex) {
        return ex instanceof InvocationTargetException call && call.getCause() != null ? call.getCause() : ex;
    }
}
