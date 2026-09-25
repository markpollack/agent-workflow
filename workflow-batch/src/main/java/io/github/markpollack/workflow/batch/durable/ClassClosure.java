package io.github.markpollack.workflow.batch.durable;

import java.io.*;
import java.lang.reflect.*;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.regex.*;

/** Conservative symbolic linkage check. Dynamic reflection/resource names remain explicit packaging obligations. */
final class ClassClosure {
    private static final Pattern DESCRIPTOR=Pattern.compile("L([a-zA-Z_$][a-zA-Z0-9_$/]*)");
    private ClassClosure() {}
    static void verify(Map<String,byte[]> image,Collection<String> roots,ClassLoader loader) {
        Deque<String> pending=new ArrayDeque<>(roots);Set<String> seen=new HashSet<>();
        while(!pending.isEmpty()) {
            String name=pending.removeFirst().replace('/','.');
            if(!seen.add(name)) continue;
            if(name.startsWith("[")) { descriptors(name,pending);continue; }
            try {
                if(platform(name)) { Class.forName(name,false,ClassLoader.getPlatformClassLoader());continue; }
                if(name.equals(DurableOperation.class.getName())||name.equals(DeliveryContext.class.getName())) continue;
                byte[] bytes=image.get(name.replace('.','/')+".class");
                if(bytes==null) throw new WorkflowRefusal("ARTIFACT_UNAVAILABLE","statically referenced class not retained: "+name);
                Class<?> caller=Class.forName(name,false,loader);
                ClassInfo info=references(bytes);pending.addAll(info.classes);
                for(MemberReference member:info.members) verifyMember(caller,member,loader);
            } catch(ReflectiveOperationException|IOException|LinkageError ex) {
                throw new WorkflowRefusal("ARTIFACT_UNAVAILABLE","retained class linkage failed: "+name+": "+ex.getMessage(),ex);
            }
        }
    }
    static void types(Type type,Collection<String> roots) {
        if(type instanceof Class<?> c) { if(!c.isPrimitive()) roots.add(c.getName()); }
        else if(type instanceof ParameterizedType p) { types(p.getRawType(),roots);for(Type arg:p.getActualTypeArguments()) types(arg,roots); }
    }
    private static boolean platform(String name) { return name.startsWith("java.")||name.startsWith("javax.")||name.startsWith("jdk.")||name.startsWith("sun."); }
    private static void descriptors(String descriptor,Collection<String> names) {
        Matcher matcher=DESCRIPTOR.matcher(descriptor);
        while(matcher.find()) names.add(matcher.group(1));
    }
    private record MemberReference(String owner,String name,String descriptor,int kind,Boolean isStatic) {}
    private record ClassInfo(Set<String> classes,List<MemberReference> members) {}
    private static ClassInfo references(byte[] bytes) throws IOException {
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(in.readInt()!=0xcafebabe) throw new IOException("invalid class file");
            in.readUnsignedShort();in.readUnsignedShort();int size=in.readUnsignedShort();
            int[] tags=new int[size],left=new int[size],right=new int[size];String[] utf=new String[size];
            for(int i=1;i<size;i++) {
                tags[i]=in.readUnsignedByte();
                switch(tags[i]) {
                    case 1 -> utf[i]=in.readUTF();
                    case 3,4 -> in.readInt();
                    case 5,6 -> {in.readLong();i++;}
                    case 7,8,16,19,20 -> left[i]=in.readUnsignedShort();
                    case 9,10,11,12,17,18 -> {left[i]=in.readUnsignedShort();right[i]=in.readUnsignedShort();}
                    case 15 -> {left[i]=in.readUnsignedByte();right[i]=in.readUnsignedShort();}
                    default -> throw new IOException("unknown constant pool tag");
                }
            }
            Set<String> names=new LinkedHashSet<>();
            Map<Integer,Boolean> staticUse=new HashMap<>();
            // Follow actual symbolic descriptors; arbitrary UTF/string constants are not declarations.
            for(int i=1;i<size;i++) {
                if(tags[i]==12) descriptors(utf[right[i]],names);
                if(tags[i]==16) descriptors(utf[left[i]],names);
                if(tags[i]==15) staticUse.put(right[i],left[i]==2||left[i]==4||left[i]==6);
            }
            for(int i=1;i<size;i++) if(tags[i]>=9&&tags[i]<=11) names.add(utf[left[left[i]]]);
            in.readUnsignedShort();in.readUnsignedShort();int parent=in.readUnsignedShort();if(parent!=0) names.add(utf[left[parent]]);
            int interfaces=in.readUnsignedShort();for(int i=0;i<interfaces;i++) names.add(utf[left[in.readUnsignedShort()]]);
            int fields=in.readUnsignedShort();for(int i=0;i<fields;i++) member(in,utf,left,tags,names,staticUse,false);
            int methods=in.readUnsignedShort();for(int i=0;i<methods;i++) member(in,utf,left,tags,names,staticUse,true);
            attributes(in,utf,left,tags,names,staticUse);
            List<MemberReference> members=new ArrayList<>();
            for(int i=1;i<size;i++) if(tags[i]>=9&&tags[i]<=11) members.add(new MemberReference(
                    utf[left[left[i]]],utf[left[right[i]]],utf[right[right[i]]],tags[i],staticUse.get(i)));
            return new ClassInfo(names,members);
        }
    }
    private static void member(DataInputStream in,String[] utf,int[] left,int[] tags,Set<String> names,Map<Integer,Boolean> staticUse,boolean method) throws IOException {
        int access=in.readUnsignedShort();in.readUnsignedShort();descriptors(utf[in.readUnsignedShort()],names);
        if(method&&(access&0x0100)!=0) throw new IOException("native application method unsupported");
        attributes(in,utf,left,tags,names,staticUse);
    }
    private static void attributes(DataInputStream in,String[] utf,int[] left,int[] tags,Set<String> names,Map<Integer,Boolean> staticUse) throws IOException {
        int attributes=in.readUnsignedShort();
        for(int i=0;i<attributes;i++) {
            String name=utf[in.readUnsignedShort()];int length=in.readInt();if(length<0) throw new IOException("invalid attribute");
            byte[] data=in.readNBytes(length);if(data.length!=length) throw new EOFException();
            if(name.equals("Code")) try(DataInputStream code=new DataInputStream(new ByteArrayInputStream(data))) {
                code.readUnsignedShort();code.readUnsignedShort();int count=code.readInt();if(count<0||count>data.length) throw new IOException("invalid code length");
                bytecode(code.readNBytes(count),utf,left,tags,names,staticUse);
                int exceptions=code.readUnsignedShort();for(int e=0;e<exceptions;e++) {
                    code.readUnsignedShort();code.readUnsignedShort();code.readUnsignedShort();int type=code.readUnsignedShort();if(type!=0) names.add(utf[left[type]]);
                }
            }
            else if(name.equals("Exceptions")) try(DataInputStream exceptions=new DataInputStream(new ByteArrayInputStream(data))) {
                int count=exceptions.readUnsignedShort();for(int e=0;e<count;e++) names.add(utf[left[exceptions.readUnsignedShort()]]);
            }
            else if(name.equals("Signature")) descriptors(utf[u2(data,0)],names);
        }
    }
    private static void bytecode(byte[] code,String[] utf,int[] left,int[] tags,Set<String> names,Map<Integer,Boolean> staticUse) throws IOException {
        for(int p=0;p<code.length;) {
            int op=code[p]&255,length=1,index=0;
            switch(op) {
                case 18 -> {length=2;index=code[p+1]&255;}
                case 19,20,187,189,192,193 -> {length=3;index=u2(code,p+1);}
                case 197 -> {length=4;index=u2(code,p+1);}
                case 16,21,22,23,24,25,54,55,56,57,58,169,188 -> length=2;
                case 17,132,153,154,155,156,157,158,159,160,161,162,163,164,165,166,167,168,
                        178,179,180,181,182,183,184,198,199 -> length=3;
                case 185,186,200,201 -> length=5;
                case 196 -> length=(code[p+1]&255)==132?6:4;
                case 170 -> {int aligned=(p+4)&~3;int low=i4(code,aligned+4),high=i4(code,aligned+8);length=Math.addExact(aligned-p+12,Math.multiplyExact(high-low+1,4));}
                case 171 -> {int aligned=(p+4)&~3;length=Math.addExact(aligned-p+8,Math.multiplyExact(i4(code,aligned+4),8));}
                default -> {if(op>201) throw new IOException("unsupported bytecode");}
            }
            if(op>=178&&op<=185) staticUse.put(u2(code,p+1),op==178||op==179||op==184);
            if(index!=0&&tags[index]==7) names.add(utf[left[index]]);
            if(length<=0||p+length>code.length) throw new IOException("invalid bytecode length");p+=length;
        }
    }
    private static void verifyMember(Class<?> caller,MemberReference ref,ClassLoader loader) throws ReflectiveOperationException {
        String name=ref.owner.replace('/','.');
        if(platform(name)||name.startsWith("[")) return;
        Class<?> owner=Class.forName(name,false,loader);
        Member member;
        if(ref.kind==9) {
            Class<?> type=MethodType.fromMethodDescriptorString("()"+ref.descriptor,loader).returnType();
            member=field(owner,ref.name,type,new HashSet<>());
        } else {
            if((ref.kind==11)!=owner.isInterface()) throw new NoSuchMethodException("class/interface changed: "+name);
            MethodType type=MethodType.fromMethodDescriptorString(ref.descriptor,loader);
            member=ref.name.equals("<init>")?owner.getDeclaredConstructor(type.parameterArray()):method(owner,ref.name,type,new HashSet<>());
        }
        if(member==null) throw new NoSuchMethodException("retained symbolic member missing: "+name+"."+ref.name+ref.descriptor);
        if(ref.isStatic!=null && ref.isStatic!=Modifier.isStatic(member.getModifiers()))
            throw new IllegalAccessException("retained static/instance member changed: "+member);
        if(!accessible(caller,member.getDeclaringClass(),member.getModifiers()))
            throw new IllegalAccessException("retained member access changed: "+member);
    }
    private static Member field(Class<?> owner,String name,Class<?> type,Set<Class<?>> seen) {
        if(owner==null||!seen.add(owner)) return null;
        for(Field field:owner.getDeclaredFields()) if(field.getName().equals(name)&&field.getType()==type)return field;
        for(Class<?> parent:owner.getInterfaces()) {Member result=field(parent,name,type,seen);if(result!=null)return result;}
        return field(owner.getSuperclass(),name,type,seen);
    }
    private static Member method(Class<?> owner,String name,MethodType type,Set<Class<?>> seen) {
        if(owner==null||!seen.add(owner)) return null;
        for(Method method:owner.getDeclaredMethods()) if(method.getName().equals(name)&&method.getReturnType()==type.returnType()
                &&Arrays.equals(method.getParameterTypes(),type.parameterArray()))return method;
        Member inherited=method(owner.getSuperclass(),name,type,seen);if(inherited!=null)return inherited;
        for(Class<?> parent:owner.getInterfaces()) {Member result=method(parent,name,type,seen);if(result!=null)return result;}
        return null;
    }
    private static boolean accessible(Class<?> caller,Class<?> owner,int modifiers) {
        if(Modifier.isPublic(modifiers))return Modifier.isPublic(owner.getModifiers())||caller.getPackageName().equals(owner.getPackageName());
        if(Modifier.isPrivate(modifiers))return caller==owner||caller.isNestmateOf(owner);
        return caller.getPackageName().equals(owner.getPackageName())||(Modifier.isProtected(modifiers)&&owner.isAssignableFrom(caller));
    }
    private static int u2(byte[] data,int p) { return ((data[p]&255)<<8)|(data[p+1]&255); }
    private static int i4(byte[] data,int p) { return (data[p]<<24)|((data[p+1]&255)<<16)|((data[p+2]&255)<<8)|(data[p+3]&255); }
}
