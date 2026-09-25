package io.github.markpollack.workflow.batch.durable;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.workflow.flows.compiler.ExecutableIdentity;
import io.github.markpollack.workflow.flows.compiler.TypeContracts;

/**
 * A bounded local execution image, captured from explicit class directories/JARs and the fixed
 * codec closure. All bytes are retained at admission. Loading uses only the verified image,
 * Java platform classes and the two leaf API types; there is no ambient application fallback.
 * Reflection/service/resource dependencies must be included. Native libraries and manifest
 * Class-Path expansion are unsupported. External environment and services remain external facts.
 */
public final class ExecutableBundle {
    private static final int FORMAT=1;
    private static final String RUNTIME="META-INF/workflow-runtime.txt";
    private static final String CODEC="META-INF/workflow-codec.txt";
    private final byte[] image;
    private final byte[] configuration;
    private final String closureDigest;
    private final String configurationDigest;
    private final String codecDigest;

    private ExecutableBundle(byte[] image,byte[] configuration) {
        this.image=image.clone(); this.configuration=configuration.clone();
        closureDigest=Digests.of(image); configurationDigest=Digests.of(configuration);
        Map<String,byte[]> entries=unpack(image);
        if(!Arrays.equals(entries.get(RUNTIME),runtime())) throw new WorkflowRefusal("RUNTIME_CHANGED","Java runtime selection differs");
        for(Class<?> api:List.of(DurableOperation.class,DeliveryContext.class))
            if(!Arrays.equals(entries.get("META-INF/workflow-api/"+api.getName()),apiBytes(api)))
                throw new WorkflowRefusal("RUNTIME_CHANGED","shared leaf API bytes differ: "+api.getName());
        byte[] codec=entries.get(CODEC);
        if(codec==null) throw new WorkflowRefusal("CODEC_MISSING","codec closure selection is missing");
        codecDigest=new String(codec,java.nio.charset.StandardCharsets.UTF_8);
        readConfiguration(configuration);
    }

    /** Capture explicit application artifacts and immutable configuration; no handler is invoked. */
    public static ExecutableBundle capture(List<Path> applicationClasspath,Map<String,String> configuration) {
        Objects.requireNonNull(applicationClasspath,"application classpath");
        try {
            TreeMap<String,byte[]> entries=new TreeMap<>();
            Set<Path> codecPaths=new LinkedHashSet<>();
            for(Class<?> type:List.of(TypeContracts.class,ObjectMapper.class,JsonFactory.class,JsonProperty.class))
                codecPaths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
            for(Path path:codecPaths) addArtifact(entries,path);
            String codecDigest=Digests.of(pack(entries));
            for(Path path:List.copyOf(applicationClasspath)) if(!codecPaths.contains(path)) addArtifact(entries,path);
            for(Class<?> api:List.of(DurableOperation.class,DeliveryContext.class))
                put(entries,"META-INF/workflow-api/"+api.getName(),apiBytes(api));
            put(entries,RUNTIME,runtime()); put(entries,CODEC,codecDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new ExecutableBundle(pack(entries),configuration(configuration));
        } catch(Exception ex) {
            if(ex instanceof WorkflowRefusal refusal) throw refusal;
            throw new WorkflowRefusal("ARTIFACT_CAPTURE","cannot capture executable closure",ex);
        }
    }

    /** Select a concrete operation from these exact closure/configuration bytes for compilation. */
    public ExecutableIdentity selection(Class<? extends DurableOperation<?,?>> operation,Type input,Type output) {
        return selection(operation.getName(),input,output);
    }
    public ExecutableIdentity selection(String entryPoint,Type input,Type output) {
        return new ExecutableIdentity(entryPoint,closureDigest,configurationDigest,input,output);
    }
    public String closureDigest() { return closureDigest; }
    public String configurationDigest() { return configurationDigest; }
    public String codecDigest() { return codecDigest; }
    byte[] image() { return image.clone(); }
    byte[] configurationBytes() { return configuration.clone(); }
    Map<String,String> configuration() { return readConfiguration(configuration); }
    Map<String,byte[]> entries() { return unpack(image); }

    static ExecutableBundle restore(byte[] image,byte[] configuration,String closure,String config,String codec) {
        if(image==null||configuration==null||!Digests.of(image).equals(closure)||!Digests.of(configuration).equals(config))
            throw new WorkflowRefusal("ARTIFACT_CHANGED","retained executable/configuration bytes missing or changed");
        ExecutableBundle result=new ExecutableBundle(image,configuration);
        if(!result.codecDigest.equals(codec)) throw new WorkflowRefusal("CODEC_CHANGED","retained codec selection changed");
        return result;
    }

    private static byte[] runtime() {
        return (System.getProperty("java.runtime.version")+"\n"+System.getProperty("java.vendor")+"\n"
                +System.getProperty("java.vm.name")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static byte[] apiBytes(Class<?> api) {
        try(InputStream in=api.getResourceAsStream("/"+api.getName().replace('.','/')+".class")) {
            if(in==null) throw new IOException("API class resource unavailable");return in.readAllBytes();
        } catch(IOException ex) {throw new WorkflowRefusal("ARTIFACT_UNAVAILABLE","cannot verify shared leaf API",ex);}
    }

    private static void addArtifact(Map<String,byte[]> entries,Path path) throws IOException {
        if(Files.isDirectory(path)) {
            try(var paths=Files.walk(path)) {
                for(Path file:paths.filter(Files::isRegularFile).sorted().toList())
                    put(entries,path.relativize(file).toString().replace(File.separatorChar,'/'),Files.readAllBytes(file));
            }
        } else {
            byte[] original=Files.readAllBytes(path);
            put(entries,"META-INF/workflow-artifacts/"+Digests.of(original).substring(7),original);
            try(JarFile jar=new JarFile(path.toFile(),true,JarFile.OPEN_READ,Runtime.version())) {
                Manifest manifest=jar.getManifest();
                if(manifest!=null && manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH)!=null)
                    throw new IOException("manifest Class-Path unsupported: "+path);
                for(JarEntry entry:jar.versionedStream().filter(e->!e.isDirectory()).toList()) {
                    String name=entry.getName();
                    if(name.equals("META-INF/MANIFEST.MF")||name.equals("module-info.class")
                            ||name.startsWith("META-INF/LICENSE")||name.startsWith("META-INF/NOTICE")
                            ||name.startsWith("META-INF/maven/")
                            ||name.matches("META-INF/[^/]+\\.(SF|RSA|DSA)")) continue;
                    try(InputStream in=jar.getInputStream(entry)) { put(entries,name,in.readAllBytes()); }
                }
            }
        }
    }
    private static void put(Map<String,byte[]> entries,String name,byte[] bytes) {
        if(name.endsWith(".so")||name.endsWith(".dll")||name.endsWith(".dylib"))
            throw new WorkflowRefusal("PACKAGING_UNSUPPORTED","native library unsupported: "+name);
        byte[] previous=entries.putIfAbsent(name,bytes);
        if(previous!=null&&!Arrays.equals(previous,bytes))
            throw new WorkflowRefusal("ARTIFACT_AMBIGUOUS","duplicate resource with different bytes: "+name);
    }
    private static byte[] pack(Map<String,byte[]> entries) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)) {
                out.writeInt(FORMAT);out.writeInt(entries.size());
                for(var entry:new TreeMap<>(entries).entrySet()) {
                    out.writeUTF(entry.getKey());out.writeInt(entry.getValue().length);out.write(entry.getValue());
                }
            }
            return bytes.toByteArray();
        } catch(IOException ex) { throw new UncheckedIOException(ex); }
    }
    private static Map<String,byte[]> unpack(byte[] bytes) {
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=FORMAT) throw new IOException("unknown image format");
            int count=in.readInt(); if(count<1||count>100_000) throw new IOException("invalid image size");
            Map<String,byte[]> entries=new LinkedHashMap<>();
            for(int i=0;i<count;i++) {
                String name=in.readUTF(); int length=in.readInt();
                if(length<0||length>in.available()) throw new IOException("invalid resource size");
                if(entries.put(name,in.readNBytes(length))!=null) throw new IOException("duplicate image resource");
            }
            if(in.available()!=0) throw new IOException("trailing image bytes");
            return entries;
        } catch(IOException ex) { throw new WorkflowRefusal("ARTIFACT_INVALID","invalid retained image",ex); }
    }
    private static byte[] configuration(Map<String,String> configuration) {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)) {
                var copy=new TreeMap<>(Map.copyOf(configuration));out.writeInt(copy.size());
                for(var entry:copy.entrySet()) { out.writeUTF(entry.getKey());out.writeUTF(entry.getValue()); }
            }
            return bytes.toByteArray();
        } catch(IOException ex) { throw new WorkflowRefusal("CONFIGURATION_INVALID","invalid configuration",ex); }
    }
    private static Map<String,String> readConfiguration(byte[] bytes) {
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count=in.readInt();if(count<0||count>10_000) throw new IOException("configuration size");
            Map<String,String> values=new TreeMap<>();
            for(int i=0;i<count;i++) if(values.put(in.readUTF(),in.readUTF())!=null) throw new IOException("duplicate key");
            if(in.available()!=0) throw new IOException("trailing configuration");
            return Map.copyOf(values);
        } catch(IOException ex) { throw new WorkflowRefusal("CONFIGURATION_INVALID","invalid retained configuration",ex); }
    }
}
