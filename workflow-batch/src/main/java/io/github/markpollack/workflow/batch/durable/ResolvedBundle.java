package io.github.markpollack.workflow.batch.durable;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import io.github.markpollack.workflow.flows.compiler.*;

/** Per-admission/recovery verified image. No class or resource bytes are reread from deployment paths. */
final class ResolvedBundle {
    private final ImageLoader loader;
    private final Object codec;
    private final Class<?> codecClass;
    private final Map<String,String> configuration;
    private final Map<String,Class<?>> operations=new HashMap<>();

    ResolvedBundle(ExecutableBundle bundle,ValidatedWorkflow workflow) {
        loader=new ImageLoader(bundle.entries()); configuration=bundle.configuration();
        Set<String> roots=new LinkedHashSet<>();
        for(var value:workflow.values().values()) ClassClosure.types(value.declaration(),roots);
        for(var call:workflow.invocations()) roots.add(call.executable().entryPoint());
        ClassClosure.verify(bundle.entries(),roots,loader);
        try {
            codecClass=loader.loadClass(TypeContracts.class.getName());
            codec=codecClass.getConstructor().newInstance();
            Object identity=codecClass.getMethod("identity").invoke(codec);
            var expected=workflow.codecIdentity();
            if(!expected.name().equals(access(identity,"name"))||!expected.version().equals(access(identity,"version"))
                    ||!expected.configuration().equals(access(identity,"configuration")))
                throw new WorkflowRefusal("CODEC_CHANGED","retained codec contract differs");
            for(var value:workflow.values().values()) {
                Object contract=codecClass.getMethod("contract",Type.class).invoke(codec,type(value.declaration()));
                if(!value.contract().javaType().equals(access(contract,"javaType"))
                        ||!value.contract().shapeDigest().equals(access(contract,"shapeDigest")))
                    throw new WorkflowRefusal("TYPE_CHANGED","retained declared shape differs: "+value.identity());
            }
            for(var call:workflow.invocations()) {
                var selected=call.executable();
                if(!bundle.closureDigest().equals(selected.artifactClosureDigest())
                        ||!bundle.configurationDigest().equals(selected.configurationDigest()))
                    throw new WorkflowRefusal("EXECUTABLE_CHANGED","selected closure/configuration differs at "+call.placement());
                Class<?> operation=Class.forName(selected.entryPoint(),false,loader);
                if(!Modifier.isPublic(operation.getModifiers())||Modifier.isAbstract(operation.getModifiers()))
                    throw new WorkflowRefusal("EXECUTABLE_CONTRACT","concrete public operation required");
                operation.getConstructor();
                boolean witnessed=false;
                for(Type parent:operation.getGenericInterfaces()) if(parent instanceof ParameterizedType p
                        &&p.getRawType()==DurableOperation.class) {
                    Type[] args=p.getActualTypeArguments();
                    witnessed=args[0].equals(type(selected.input()))&&args[1].equals(type(selected.output()));
                }
                if(!witnessed) throw new WorkflowRefusal("EXECUTABLE_CONTRACT","direct concrete DurableOperation contract required: "+selected.entryPoint());
                operations.put(selected.entryPoint(),operation);
            }
        } catch(WorkflowRefusal ex) { throw ex; }
        catch(ReflectiveOperationException|LinkageError ex) {
            throw new WorkflowRefusal("ARTIFACT_UNAVAILABLE","retained executable/codec resolution failed",unwrap(ex));
        }
    }

    Object decode(byte[] bytes,Type declaration) {
        try { return codecClass.getMethod("decodeBytes",byte[].class,Type.class).invoke(codec,bytes,type(declaration)); }
        catch(ReflectiveOperationException|LinkageError ex) { throw new WorkflowRefusal("DECODE_FAILED","retained value cannot decode",unwrap(ex)); }
    }
    byte[] encode(Object value,Type declaration) {
        try { return (byte[])codecClass.getMethod("encodeBytes",Object.class,Type.class).invoke(codec,value,type(declaration)); }
        catch(ReflectiveOperationException|LinkageError ex) { throw new WorkflowRefusal("ENCODE_FAILED","result cannot encode losslessly",unwrap(ex)); }
    }
    Object assemble(Type declaration,List<Object> components) {
        Type resolved=type(declaration);
        Class<?> raw=resolved instanceof Class<?> c?c:(Class<?>)((ParameterizedType)resolved).getRawType();
        if(!raw.isRecord()) throw new WorkflowRefusal("BINDING_INVALID","assembly requires a declared record");
        try {
            Class<?>[] parameters=Arrays.stream(raw.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new);
            return raw.getConstructor(parameters).newInstance(components.toArray());
        } catch(ReflectiveOperationException ex) { throw new WorkflowRefusal("INPUT_FAILED","record input construction failed",unwrap(ex)); }
    }
    @SuppressWarnings("unchecked") // Compiler and direct generic declaration were checked before admission/dispatch.
    Object execute(String entry,Object input,DeliveryContext context) throws Exception {
        ClassLoader previous=Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            DurableOperation<Object,Object> operation=(DurableOperation<Object,Object>)operations.get(entry).getConstructor().newInstance();
            return operation.execute(input,context,configuration);
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
    private Type type(Type original) {
        if(original instanceof Class<?> c) {
            if(c.isPrimitive()) return c;
            try { return loader.loadClass(c.getName()); }
            catch(ClassNotFoundException ex) { throw new WorkflowRefusal("TYPE_UNAVAILABLE","retained type missing: "+c.getName(),ex); }
        }
        if(original instanceof ParameterizedType p) return new ConcreteType(type(p.getRawType()),
                p.getOwnerType()==null?null:type(p.getOwnerType()),Arrays.stream(p.getActualTypeArguments()).map(this::type).toArray(Type[]::new));
        throw new WorkflowRefusal("TYPE_UNSUPPORTED","unresolved declaration");
    }
    private record ConcreteType(Type raw,Type owner,Type[] arguments) implements ParameterizedType {
        @Override public Type getRawType() { return raw; }
        @Override public Type getOwnerType() { return owner; }
        @Override public Type[] getActualTypeArguments() { return arguments.clone(); }
        @Override public boolean equals(Object other) { return other instanceof ParameterizedType p&&raw.equals(p.getRawType())
                &&Objects.equals(owner,p.getOwnerType())&&Arrays.equals(arguments,p.getActualTypeArguments()); }
        @Override public int hashCode() { return raw.hashCode()^Objects.hashCode(owner)^Arrays.hashCode(arguments); }
        @Override public String getTypeName() { return raw.getTypeName()+"<"+String.join(",",Arrays.stream(arguments).map(Type::getTypeName).toList())+">"; }
    }
    private static String access(Object object,String method) throws ReflectiveOperationException {
        return (String)object.getClass().getMethod(method).invoke(object);
    }
    private static Throwable unwrap(Throwable ex) {
        return ex instanceof InvocationTargetException call&&call.getCause()!=null?call.getCause():ex;
    }
    private static final class ImageLoader extends ClassLoader {
        private final Map<String,byte[]> entries;
        ImageLoader(Map<String,byte[]> entries) { super(ClassLoader.getPlatformClassLoader());this.entries=entries; }
        @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            synchronized(getClassLoadingLock(name)) {
                if(name.equals(DurableOperation.class.getName())) return DurableOperation.class;
                if(name.equals(DeliveryContext.class.getName())) return DeliveryContext.class;
                Class<?> loaded=findLoadedClass(name);
                if(loaded==null) {
                    if(name.startsWith("java.")||name.startsWith("javax.")||name.startsWith("jdk.")||name.startsWith("sun."))
                        loaded=super.loadClass(name,false);
                    else {
                        byte[] bytes=entries.get(name.replace('.','/')+".class");
                        if(bytes==null) throw new ClassNotFoundException("not retained: "+name);
                        loaded=defineClass(name,bytes,0,bytes.length);
                    }
                }
                if(resolve) resolveClass(loaded);
                return loaded;
            }
        }
        @Override public InputStream getResourceAsStream(String name) {
            byte[] bytes=entries.get(name);
            return bytes==null?null:new ByteArrayInputStream(bytes);
        }
        @Override protected URL findResource(String name) {
            byte[] bytes=entries.get(name); if(bytes==null) return null;
            try { return new URL(null,"retained:/"+name,new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        @Override public void connect() {}
                        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                    };
                }
            }); } catch(MalformedURLException ex) { throw new IllegalStateException(ex); }
        }
        @Override protected Enumeration<URL> findResources(String name) {
            URL resource=findResource(name); return Collections.enumeration(resource==null?List.of():List.of(resource));
        }
    }
}
