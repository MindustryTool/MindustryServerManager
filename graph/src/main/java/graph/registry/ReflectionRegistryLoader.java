package graph.registry;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import graph.runtime.InvocationContext;
import graph.types.TypeRef;

public final class ReflectionRegistryLoader {

    private ReflectionRegistryLoader() {
    }

    public record LoadedEntry(String id, FunctionDescriptor descriptor,
                              Map<String, Invoker> invokersByHash) {

        public LoadedEntry {
            invokersByHash = Map.copyOf(invokersByHash);
        }
    }

    public static List<LoadedEntry> loadClass(Class<?> clazz, String category) {
        Objects.requireNonNull(clazz, "clazz");
        List<LoadedEntry> entries = new ArrayList<>();
        Map<String, List<Method>> byName = new LinkedHashMap<>();
        for (Method method : clazz.getMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isBridge()
                    || method.isSynthetic() || isObjectMethod(method.getName())) {
                continue;
            }
            byName.computeIfAbsent(method.getName(), k -> new ArrayList<>()).add(method);
        }
        for (Map.Entry<String, List<Method>> group : byName.entrySet()) {
            String id = clazz.getName() + "." + group.getKey();
            List<Overload> overloads = new ArrayList<>();
            Map<String, Invoker> invokers = new LinkedHashMap<>();
            for (Method method : group.getValue()) {
                try {
                    method.setAccessible(true);
                } catch (RuntimeException ignored) {
                    continue;
                }
                Overload overload = overloadOf(method);
                overloads.add(overload);
                invokers.put(overload.hash(), new CachedInvoker(method));
            }
            if (overloads.isEmpty()) {
                continue;
            }
            FunctionDescriptor template = FunctionDescriptor.builder(id)
                    .category(category != null ? category : clazz.getPackageName())
                    .ownerType(clazz.getSimpleName())
                    .displayName(group.getKey())
                    .description("Reflected from " + clazz.getName())
                    .build();
            FunctionDescriptor descriptor = withOverloads(template, overloads);
            entries.add(new LoadedEntry(id, descriptor, invokers));
        }
        return entries;
    }

    private static boolean isObjectMethod(String name) {
        switch (name) {
            case "wait", "notify", "notifyAll", "equals", "hashCode", "toString",
                    "getClass", "clone", "finalize":
                return true;
            default:
                return false;
        }
    }

    static FunctionDescriptor withOverloads(FunctionDescriptor template, List<Overload> overloads) {
        return new FunctionDescriptor(template.id(), template.displayName(),
                template.category(), template.ownerType(), overloads,
                template.threadRequirement(), template.codegenSafe(), template.advanced(),
                template.deprecated(), template.sinceVersion(), template.description(),
                template.aliases());
    }

    public static Overload overloadOf(Method method) {
        ParamDescriptor[] params = new ParamDescriptor[method.getParameterCount()];
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            params[i] = new ParamDescriptor("arg" + i, TypeRef.of(simpleName(types[i])));
        }
        return new Overload(List.of(params), returnType(method.getReturnType()));
    }

    static TypeRef returnType(Class<?> type) {
        return type == void.class ? TypeRef.of("Void") : TypeRef.of(simpleName(type));
    }

    public static String simpleName(Class<?> type) {
        if (type == String.class) {
            return "String";
        }
        if (type == int.class) {
            return "Int";
        }
        if (type == long.class) {
            return "Long";
        }
        if (type == float.class) {
            return "Float";
        }
        if (type == double.class) {
            return "Double";
        }
        if (type == boolean.class) {
            return "Boolean";
        }
        if (type == byte.class) {
            return "Byte";
        }
        if (type.isAnonymousClass() || type.getSimpleName().isEmpty()) {
            return "Object";
        }
        return type.getSimpleName();
    }

    public static final class CachedInvoker implements Invoker {

        private final Method method;
        private final boolean isStatic;

        public CachedInvoker(Method method) {
            this.method = method;
            this.isStatic = Modifier.isStatic(method.getModifiers());
        }

        public Method method() {
            return method;
        }

        @Override
        public Object invoke(String overloadHash, Object[] args, InvocationContext ctx)
                throws Exception {
            ctx.checkCancelled();
            try {
                if (isStatic) {
                    return method.invoke(null, args);
                }
                Object receiver = args != null && args.length > 0 ? args[0] : null;
                Object[] rest = tail(args);
                return method.invoke(receiver, rest);
            } catch (InvocationTargetException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }

        private static Object[] tail(Object[] args) {
            if (args == null || args.length == 0) {
                return new Object[0];
            }
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return rest;
        }
    }

    public static final class OverloadDispatchInvoker implements Invoker {

        private final Map<String, Invoker> byHash;
        private volatile String defaultHash;

        public OverloadDispatchInvoker(Map<String, Invoker> byHash) {
            this.byHash = Map.copyOf(byHash);
            for (String hash : this.byHash.keySet()) {
                defaultHash = hash;
                break;
            }
        }

        public void setDefaultHash(String hash) {
            if (byHash.containsKey(hash)) {
                defaultHash = hash;
            }
        }

        @Override
        public Object invoke(String overloadHash, Object[] args, InvocationContext ctx)
                throws Exception {
            Invoker invoker = byHash.get(overloadHash != null && !overloadHash.isEmpty()
                    ? overloadHash : defaultHash);
            if (invoker == null) {
                throw new IllegalArgumentException(
                        "No registered overload for hash '" + overloadHash + "'");
            }
            return invoker.invoke(overloadHash, args, ctx);
        }
    }
}
