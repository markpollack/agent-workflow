package io.github.markpollack.workflow.flows.r1probe.binding;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** A test-only compiler model. It invokes no application code and owns no durable store. */
final class TypedPlan {

    private TypedPlan() {
    }

    abstract static class TypeRef<T> {
        final Type type = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[0];
    }

    record ListType(Type element) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return new Type[] { element }; }
        @Override public Type getRawType() { return List.class; }
        @Override public Type getOwnerType() { return null; }
        @Override public String getTypeName() { return "java.util.List<" + element.getTypeName() + ">"; }
        @Override public boolean equals(Object other) {
            return other instanceof ParameterizedType p && p.getRawType() == List.class
                    && Arrays.equals(getActualTypeArguments(), p.getActualTypeArguments());
        }
        @Override public int hashCode() { return Arrays.hashCode(getActualTypeArguments()) ^ List.class.hashCode(); }
    }

    record Fact(String id, Type type, List<Fact> parts, String identity, List<Fact> consumed) {
        Fact { parts = List.copyOf(parts); consumed = List.copyOf(consumed); supported(type); }
        Fact(String id, Type type, List<Fact> parts, String identity) {
            this(id, type, parts, identity, List.of());
        }
    }

    record Dispatch(String placement, Fact input, Fact output, String mapping) {
    }

    record Capture(String name, Fact fact, List<Fact> arms) {
        Capture { arms = List.copyOf(arms); }
    }

    static final class Refusal extends IllegalArgumentException {
        Refusal(String message) { super(message); }
    }

    static void supported(Type type) {
        if (type == Object.class || type instanceof Class<?> c && java.util.Collection.class.isAssignableFrom(c)) {
            throw new Refusal("Concrete element contract required: " + type.getTypeName());
        }
        if (type instanceof ParameterizedType p) {
            for (Type element : p.getActualTypeArguments()) supported(element);
        }
        else if (!(type instanceof Class<?>)) {
            throw new Refusal("Unresolved contract: " + type.getTypeName());
        }
    }

    static final class Scope {
        final String name;
        final List<Fact> facts = new ArrayList<>();
        final Set<Fact> superseded = new HashSet<>();
        final Map<Type, String> missing = new LinkedHashMap<>();
        final Map<Type, List<String>> unproven = new LinkedHashMap<>();
        final Map<String, Dispatch> dispatches = new LinkedHashMap<>();
        final List<Capture> captures = new ArrayList<>();
        Fact carrier;
        boolean terminated;

        Scope(String name, Type root) {
            this.name = segment(name);
            carrier = fact("root", root, List.of());
            facts.add(carrier);
        }

        private Scope(String name, Scope outer) {
            this.name = name;
            facts.addAll(outer.facts);
            superseded.addAll(outer.superseded);
            missing.putAll(outer.missing);
            unproven.putAll(outer.unproven);
            carrier = outer.carrier;
        }

        Scope fork(String name) { return new Scope(this.name + "/" + segment(name), this); }

        private Fact fact(String id, Type type, List<Fact> parts) {
            return new Fact(id, type, parts, name + "/" + segment(id));
        }

        Dispatch call(String placement, Type input, Type output) {
            if (dispatches.containsKey(placement)) throw new Refusal("Duplicate placement: " + placement);
            Fact effective = resolve(input, placement + ".in", true);
            String mapping = effective.id().equals(placement + ".in") && !effective.parts().isEmpty()
                    ? expression(effective) : effective.id();
            if (!facts.contains(effective)) facts.add(effective);
            Fact result = new Fact(placement + ".out", output, List.of(), name + "/" + segment(placement + ".out"), List.of(effective));
            // A value directly consumed and rewritten is one state lineage, not an unrelated role.
            if (effective.type().equals(output)) superseded.add(effective);
            for (Fact component : effective.parts()) {
                if (component.type().equals(output)) superseded.add(component);
            }
            facts.add(result);
            missing.remove(output);
            unproven.remove(output);
            carrier = result;
            Dispatch dispatch = new Dispatch(placement, effective, result, mapping);
            dispatches.put(placement, dispatch);
            return dispatch;
        }

        Dispatch control(String placement, Type input, Type result) {
            Fact before = carrier;
            Dispatch dispatch = call(placement, input, result);
            carrier = before;
            return dispatch;
        }

        Fact resolve(Type wanted, String at, boolean assemble) {
            supported(wanted);
            if (carrier != null && carrier.type().equals(wanted)) return carrier;
            if (missing.containsKey(wanted)) {
                throw new Refusal(name + "/" + at + ": missing assignment of " + wanted.getTypeName()
                        + " on continuing arm " + missing.get(wanted));
            }
            if (unproven.containsKey(wanted)) {
                throw new Refusal(name + "/" + at + ": unproven capture role " + wanted.getTypeName()
                        + "; candidates=" + unproven.get(wanted));
            }
            List<Fact> matches = candidates(wanted);
            if (matches.size() == 1) return matches.getFirst();
            if (matches.size() > 1) {
                throw new Refusal(name + "/" + at + ": ambiguous " + wanted.getTypeName()
                        + "; legal candidates=" + matches.stream().map(Fact::id).toList());
            }
            if (assemble && wanted instanceof Class<?> record && record.isRecord()) {
                var components = record.getRecordComponents();
                if (components.length > 0) {
                    List<Fact> inputs = new ArrayList<>();
                    for (var component : components) {
                        inputs.add(resolve(component.getGenericType(), at + "." + component.getName(), false));
                    }
                    return fact(at, wanted, inputs);
                }
            }
            throw new Refusal(name + "/" + at + ": no legally visible " + wanted.getTypeName());
        }

        List<Fact> candidates(Type wanted) {
            return facts.stream().filter(f -> f.type().equals(wanted) && !superseded.contains(f)).distinct().toList();
        }

        void merge(String placement, Scope... paths) {
            List<Scope> live = Arrays.stream(paths).filter(p -> !p.terminated).toList();
            if (live.isEmpty()) { terminated = true; return; }
            Set<Type> types = new LinkedHashSet<>();
            for (Scope path : live) {
                path.facts.stream().filter(f -> !facts.contains(f)).forEach(f -> types.add(f.type()));
            }
            Fact nextCarrier = null;
            for (Type type : types) {
                List<Fact> selected = new ArrayList<>();
                String absent = null;
                for (Scope path : live) {
                    List<Fact> local = path.facts.stream().filter(f -> f.type().equals(type)
                            && !facts.contains(f) && !path.superseded.contains(f)).toList();
                    if (path.carrier != null && path.carrier.type().equals(type)) selected.add(path.carrier);
                    else if (local.size() == 1) selected.add(local.getFirst());
                    else if (local.size() > 1) {
                        throw new Refusal(path.name + ": competing capture roles "
                                + local.stream().map(Fact::id).toList());
                    }
                    else {
                        List<Fact> incoming = path.candidates(type);
                        if (incoming.size() == 1) selected.add(incoming.getFirst());
                        else if (incoming.isEmpty()) absent = path.name;
                        else throw new Refusal(path.name + ": ambiguous unchanged role " + type.getTypeName());
                    }
                }
                if (absent != null) { missing.put(type, absent); continue; }
                if (selected.stream().distinct().count() == 1) {
                    Fact sole = selected.getFirst();
                    if (!facts.contains(sole)) facts.add(sole);
                    if (live.stream().allMatch(p -> p.carrier.equals(sole))) nextCarrier = sole;
                    continue;
                }
                if (!live.stream().allMatch(p -> p.carrier.type().equals(type))) {
                    unproven.put(type, selected.stream().map(Fact::identity).toList());
                    continue;
                }
                Fact capture = fact(placement + ".capture<" + shortType(type) + ">", type, List.of());
                captures.add(new Capture(placement, capture, selected));
                for (Fact earlier : facts) {
                    if (earlier.type().equals(type) && (earlier.equals(carrier)
                            || selected.stream().allMatch(value -> consumes(value, earlier)))) {
                        superseded.add(earlier);
                    }
                }
                facts.add(capture);
                if (live.stream().allMatch(p -> p.carrier.type().equals(type))) nextCarrier = capture;
            }
            if (nextCarrier != null) carrier = nextCarrier;
        }

        Fact join(String placement, Scope... members) {
            if (members.length == 0) throw new Refusal("Static group requires members");
            List<Fact> results = Arrays.stream(members).map(m -> m.carrier).toList();
            boolean homogeneous = results.stream().map(Fact::type).distinct().count() == 1;
            if (homogeneous) {
                Fact list = fact(placement + ".aggregate", new ListType(results.getFirst().type()), results);
                facts.add(list);
                carrier = list;
                return list;
            }
            // The product exposes only settled result roles, never a member's private facts.
            for (Fact result : results) facts.add(result);
            carrier = null;
            return null;
        }

        Scope iteration(String placement, int ordinal) {
            if (ordinal < 1) throw new Refusal("Iteration coordinates start at one");
            return new Scope(name + "/" + segment(placement) + "[" + ordinal + "]", this);
        }

        void carry(String placement, Scope completed) {
            Fact value = completed.carrier;
            facts.stream().filter(f -> f.type().equals(value.type())
                    && (f.equals(carrier) || consumes(value, f))).forEach(superseded::add);
            Fact result = new Fact(placement + ".result", value.type(), List.of(value), value.identity() + "/carry");
            facts.add(result);
            carrier = result;
        }

        Scope item(String placement, Type element) {
            Scope member = fork(placement);
            Fact item = member.fact(placement + ".item", element, List.of());
            member.facts.add(item);
            member.carrier = item;
            return member;
        }

        static Scope child(String name, Type root) { return new Scope(name, root); }

        Fact terminal(Type expected) {
            Fact result = resolve(expected, "terminal", false);
            terminated = true;
            return result;
        }
    }

    static String expression(Fact input) {
        return shortType(input.type()) + "(" + input.parts().stream().map(Fact::id).collect(Collectors.joining(",")) + ")";
    }

    static String shortType(Type type) {
        return type instanceof Class<?> c ? c.getSimpleName() : type.getTypeName();
    }

    private static boolean consumes(Fact value, Fact earlier) {
        if (value.equals(earlier)) return true;
        return value.parts().stream().anyMatch(part -> consumes(part, earlier))
                || value.consumed().stream().anyMatch(input -> consumes(input, earlier));
    }

    private static String segment(String authored) {
        return authored.replace("%", "%25").replace("/", "%2F").replace("[", "%5B").replace("]", "%5D");
    }
}
