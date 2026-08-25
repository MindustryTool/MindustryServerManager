package plugin.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class GraphIndexProcessor extends AbstractProcessor {

    private static final String GENERATED_CLASS = "plugin.core.GeneratedGraphIndex";

    private final Map<String, List<String>> functions = new TreeMap<>();
    private final Map<String, List<String>> events = new TreeMap<>();
    private final Map<String, List<String>> properties = new TreeMap<>();
    private final Map<String, List<String>> constructors = new TreeMap<>();
    private boolean generated;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_17;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver() || generated) {
            return false;
        }

        collect(roundEnv.getElementsAnnotatedWith(plugin.annotations.GraphFunction.class), "function");
        collect(roundEnv.getElementsAnnotatedWith(plugin.annotations.GraphEvent.class), "event");
        collect(roundEnv.getElementsAnnotatedWith(plugin.annotations.GraphProperty.class), "property");
        collect(roundEnv.getElementsAnnotatedWith(plugin.annotations.GraphConstructor.class), "constructor");

        boolean any = !functions.isEmpty() || !events.isEmpty()
                || !properties.isEmpty() || !constructors.isEmpty();
        if (any) {
            writeIndex();
            generated = true;
        }

        return false;
    }

    private void collect(Set<? extends Element> elements, String kind) {
        for (Element element : elements) {
            if (!(element instanceof ExecutableElement method) || method.getKind() != ElementKind.METHOD) {
                continue;
            }
            AnnotationMirror mirror = findMirror(method, kind);
            if (mirror == null) {
                continue;
            }
            Map<String, AnnotationValue> values = mapValues(mirror);
            String id = asString(values.get("id"));
            if (id == null || id.isEmpty()) {
                processingEnv.getMessager().printMessage(Kind.ERROR,
                        "Graph annotation requires non-empty id", method);
                continue;
            }
            switch (kind) {
                case "function" -> putUnique(functions, id, functionCells(method, values), method);
                case "event" -> putUnique(events, id, eventCells(method, values), method);
                case "property" -> putUnique(properties, id, propertyCells(method, values), method);
                case "constructor" -> putUnique(constructors, id, constructorCells(method, values), method);
                default -> throw new IllegalStateException(kind);
            }
        }
    }

    private AnnotationMirror findMirror(ExecutableElement method, String kind) {
        String canonical = ("function".equals(kind)) ? plugin.annotations.GraphFunction.class.getCanonicalName()
                : "event".equals(kind) ? plugin.annotations.GraphEvent.class.getCanonicalName()
                : "property".equals(kind) ? plugin.annotations.GraphProperty.class.getCanonicalName()
                : plugin.annotations.GraphConstructor.class.getCanonicalName();
        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(canonical)) {
                return mirror;
            }
        }
        return null;
    }

    private Map<String, AnnotationValue> mapValues(AnnotationMirror mirror) {
        Map<String, AnnotationValue> values = new HashMap<>();
        mirror.getElementValues().forEach((key, value) -> values.put(key.getSimpleName().toString(), value));
        return values;
    }

    private String asString(AnnotationValue value) {
        return value == null ? null : String.valueOf(value.getValue());
    }

    private String asStringOr(AnnotationValue value, String fallback) {
        return value == null ? fallback : String.valueOf(value.getValue());
    }

    private boolean asBoolOr(AnnotationValue value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value.getValue()));
    }

    private String[] asStringArray(AnnotationValue value) {
        if (value == null) {
            return new String[0];
        }
        @SuppressWarnings("unchecked")
        List<AnnotationValue> list = (List<AnnotationValue>) value.getValue();
        return list.stream().map(v -> String.valueOf(v.getValue())).toArray(String[]::new);
    }

    private TypeMirror asType(AnnotationValue value) {
        return value == null ? null : (TypeMirror) value.getValue();
    }

    private String[] joinAliases(AnnotationValue value) {
        String[] aliases = asStringArray(value);
        for (String alias : aliases) {
            if (alias.contains("|")) {
                processingEnv.getMessager().printMessage(Kind.ERROR,
                        "Alias must not contain '|': " + alias);
            }
        }
        return aliases;
    }

    private String categoryOf(ExecutableElement method, Map<String, AnnotationValue> values) {
        String category = asStringOr(values.get("category"), "");
        if (!category.isEmpty()) {
            return category;
        }
        Element enclosing = method.getEnclosingElement();
        while (enclosing instanceof TypeElement typeElement) {
            for (AnnotationMirror mirror : typeElement.getAnnotationMirrors()) {
                if (mirror.getAnnotationType().toString()
                        .equals(plugin.annotations.GraphCategory.class.getCanonicalName())) {
                    return asStringOr(mapValues(mirror).get("name"), "");
                }
            }
            enclosing = enclosing.getEnclosingElement();
        }
        return "";
    }

    private String ownerOf(ExecutableElement method) {
        Element enclosing = method.getEnclosingElement();
        while (!(enclosing instanceof TypeElement typeElement)) {
            enclosing = enclosing.getEnclosingElement();
            if (enclosing == null) {
                return "";
            }
        }
        return typeElement.getQualifiedName().toString();
    }

    private String signatureOf(ExecutableElement method) {
        StringBuilder sb = new StringBuilder(method.getSimpleName().toString());
        sb.append('(');
        List<? extends javax.lang.model.element.VariableElement> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params.get(i).asType());
        }
        sb.append(')');
        return sb.toString();
    }

    private List<String> functionCells(ExecutableElement method, Map<String, AnnotationValue> values) {
        return List.of(
                ownerOf(method),
                method.getSimpleName().toString(),
                signatureOf(method),
                method.getReturnType().toString(),
                categoryOf(method, values),
                asStringOr(values.get("description"), ""),
                asStringOr(values.get("threadReq"), "MAIN_THREAD"),
                String.join("|", joinAliases(values.get("aliases"))),
                String.valueOf(asBoolOr(values.get("advanced"), false)));
    }

    private List<String> eventCells(ExecutableElement method, Map<String, AnnotationValue> values) {
        TypeMirror eventType = asType(values.get("event"));
        if (eventType == null) {
            processingEnv.getMessager().printMessage(Kind.ERROR,
                    "GraphEvent requires an event class", method);
            eventType = processingEnv.getTypeUtils().getNoType(javax.lang.model.type.TypeKind.NONE);
        }
        return List.of(
                ownerOf(method),
                method.getSimpleName().toString(),
                eventType.toString(),
                asStringOr(values.get("description"), ""));
    }

    private List<String> propertyCells(ExecutableElement method, Map<String, AnnotationValue> values) {
        return List.of(
                ownerOf(method),
                method.getSimpleName().toString(),
                signatureOf(method),
                method.getReturnType().toString(),
                asStringOr(values.get("property"), ""),
                String.valueOf(asBoolOr(values.get("writable"), false)),
                asStringOr(values.get("description"), ""));
    }

    private List<String> constructorCells(ExecutableElement method, Map<String, AnnotationValue> values) {
        return List.of(
                ownerOf(method),
                method.getSimpleName().toString(),
                signatureOf(method),
                method.getReturnType().toString(),
                asStringOr(values.get("description"), ""));
    }

    private void putUnique(Map<String, List<String>> target, String id, List<String> cells,
                           ExecutableElement method) {
        List<String> existing = target.put(id, cells);
        if (existing != null) {
            processingEnv.getMessager().printMessage(Kind.ERROR,
                    "Duplicate graph registry id '" + id + "' on "
                            + ownerOf(method) + "." + method.getSimpleName());
        }
    }

    private void writeIndex() {
        Filer filer = processingEnv.getFiler();
        try {
            JavaFileObject file = filer.createSourceFile(GENERATED_CLASS);
            try (Writer writer = file.openWriter()) {
                writer.write("package plugin.core;\n\n");
                writer.write("public final class GeneratedGraphIndex {\n\n");
                writer.write("    private GeneratedGraphIndex() {\n");
                writer.write("    }\n\n");

                if (!functions.isEmpty()) {
                    writeArray(writer, "FUNCTION_IDS", keys(functions));
                    writeColumn(writer, "FUNCTION_OWNER", functions, 0);
                    writeColumn(writer, "FUNCTION_METHOD", functions, 1);
                    writeColumn(writer, "FUNCTION_SIGNATURE", functions, 2);
                    writeColumn(writer, "FUNCTION_RETURN", functions, 3);
                    writeColumn(writer, "FUNCTION_CATEGORY", functions, 4);
                    writeColumn(writer, "FUNCTION_DESCRIPTION", functions, 5);
                    writeColumn(writer, "FUNCTION_THREAD_REQ", functions, 6);
                    writeColumn(writer, "FUNCTION_ALIASES", functions, 7);
                    writeColumn(writer, "FUNCTION_ADVANCED", functions, 8);
                }

                if (!events.isEmpty()) {
                    writeArray(writer, "EVENT_IDS", keys(events));
                    writeColumn(writer, "EVENT_OWNER", events, 0);
                    writeColumn(writer, "EVENT_METHOD", events, 1);
                    writeColumn(writer, "EVENT_CLASS", events, 2);
                    writeColumn(writer, "EVENT_DESCRIPTION", events, 3);
                }

                if (!properties.isEmpty()) {
                    writeArray(writer, "PROPERTY_IDS", keys(properties));
                    writeColumn(writer, "PROPERTY_OWNER", properties, 0);
                    writeColumn(writer, "PROPERTY_METHOD", properties, 1);
                    writeColumn(writer, "PROPERTY_SIGNATURE", properties, 2);
                    writeColumn(writer, "PROPERTY_RETURN", properties, 3);
                    writeColumn(writer, "PROPERTY_NAME", properties, 4);
                    writeColumn(writer, "PROPERTY_WRITABLE", properties, 5);
                    writeColumn(writer, "PROPERTY_DESCRIPTION", properties, 6);
                }

                if (!constructors.isEmpty()) {
                    writeArray(writer, "CONSTRUCTOR_IDS", keys(constructors));
                    writeColumn(writer, "CONSTRUCTOR_OWNER", constructors, 0);
                    writeColumn(writer, "CONSTRUCTOR_METHOD", constructors, 1);
                    writeColumn(writer, "CONSTRUCTOR_SIGNATURE", constructors, 2);
                    writeColumn(writer, "CONSTRUCTOR_RETURN", constructors, 3);
                    writeColumn(writer, "CONSTRUCTOR_DESCRIPTION", constructors, 4);
                }

                writer.write("}\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate GeneratedGraphIndex", e);
        }
    }

    private static List<String> keys(Map<String, List<String>> map) {
        List<String> ids = new ArrayList<>(map.keySet());
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private static void writeArray(Writer writer, String field, List<String> values) throws IOException {
        writer.write("    public static final String[] " + field + " = {\n");
        for (String value : values) {
            writer.write("        " + quote(value) + ",\n");
        }
        writer.write("    };\n\n");
    }

    private static void writeColumn(Writer writer, String field, Map<String, List<String>> rows,
                                    int column) throws IOException {
        writer.write("    public static final String[] " + field + " = {\n");
        for (String id : keys(rows)) {
            writer.write("        " + quote(rows.get(id).get(column)) + ",\n");
        }
        writer.write("    };\n\n");
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
