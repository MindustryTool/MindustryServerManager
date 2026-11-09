package plugin.processor;

import plugin.annotations.Component;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ComponentRegistryProcessor extends AbstractProcessor {

    private static final String CONDITION_ON = "plugin.annotations.ConditionOn";
    private static final String CONDITION_ON_LIST = "plugin.annotations.ConditionOn.List";
    private static final String CLIENT_COMMAND = "plugin.annotations.ClientCommand";
    private static final String SERVER_COMMAND = "plugin.annotations.ServerCommand";
    private static final String PARAM = "plugin.annotations.Param";

    private final Map<String, TypeElement> components = new TreeMap<>();
    private final Map<String, Set<ConditionKey>> conditions = new TreeMap<>();

    private record ConditionKey(String conditionClass, List<String> args) {
        @Override
        public String toString() {
            if (args.isEmpty()) {
                return shortName(conditionClass);
            }
            return shortName(conditionClass) + "(" + String.join(", ", args) + ")";
        }
    }

    private static String shortName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

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
        if (roundEnv.processingOver()) {
            if (!components.isEmpty()) {
                validate();
                writeRegistry();
            }
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(Component.class)) {
            if (element instanceof TypeElement typeElement && typeElement.getKind() != ElementKind.ANNOTATION_TYPE) {
                String name = typeElement.getQualifiedName().toString();
                components.putIfAbsent(name, typeElement);
                conditions.putIfAbsent(name, readConditions(typeElement));
            }
        }

        return false;
    }

    private void validate() {
        Messager messager = processingEnv.getMessager();

        for (Map.Entry<String, TypeElement> entry : components.entrySet()) {
            String name = entry.getKey();
            Set<ConditionKey> own = conditions.getOrDefault(name, Set.of());

            for (String dep : dependencyNames(entry.getValue())) {
                if (!components.containsKey(dep)) {
                    continue;
                }

                Set<ConditionKey> missing = new LinkedHashSet<>(conditions.getOrDefault(dep, Set.of()));
                missing.removeAll(own);
                if (missing.isEmpty()) {
                    continue;
                }

                List<String> missingNames = missing.stream().map(ConditionKey::toString).toList();
                messager.printMessage(Diagnostic.Kind.ERROR,
                        name + " requires " + dep + " (" + String.join(", ", missingNames)
                                + ") but has no matching @ConditionOn; add @ConditionOn to " + name
                                + " or make the dependency optional",
                        entry.getValue());
            }
        }
    }

    private Set<String> dependencyNames(TypeElement type) {
        Set<String> deps = new LinkedHashSet<>();
        List<ExecutableElement> constructors = new ArrayList<>();
        List<VariableElement> fields = new ArrayList<>();

        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR) {
                constructors.add((ExecutableElement) enclosed);
            } else if (enclosed.getKind() == ElementKind.FIELD) {
                fields.add((VariableElement) enclosed);
            } else if (enclosed.getKind() == ElementKind.METHOD && isCommandMethod((ExecutableElement) enclosed)) {
                for (VariableElement param : ((ExecutableElement) enclosed).getParameters()) {
                    if (!hasAnnotation(param, PARAM)) {
                        addTypeName(deps, param.asType());
                    }
                }
            }
        }

        if (constructors.isEmpty()) {
            for (VariableElement field : fields) {
                if (field.getModifiers().contains(Modifier.FINAL) && field.getConstantValue() == null) {
                    addTypeName(deps, field.asType());
                }
            }
            return deps;
        }

        ExecutableElement selected = selectConstructor(constructors);
        if (selected != null) {
            for (VariableElement param : selected.getParameters()) {
                addTypeName(deps, param.asType());
            }
        }

        return deps;
    }

    private static ExecutableElement selectConstructor(List<ExecutableElement> constructors) {
        if (constructors.size() == 1) {
            return constructors.get(0);
        }
        for (ExecutableElement constructor : constructors) {
            if (constructor.getParameters().isEmpty()) {
                return constructor;
            }
        }
        return null;
    }

    private static boolean isCommandMethod(ExecutableElement method) {
        return hasAnnotation(method, CLIENT_COMMAND) || hasAnnotation(method, SERVER_COMMAND);
    }

    private static boolean hasAnnotation(Element element, String qualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (qualifiedName.equals(((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString())) {
                return true;
            }
        }
        return false;
    }

    private static void addTypeName(Set<String> deps, TypeMirror type) {
        String name = typeName(type);
        if (name != null) {
            deps.add(name);
        }
    }

    private static String typeName(TypeMirror type) {
        if (type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement typeElement) {
            return typeElement.getQualifiedName().toString();
        }
        return null;
    }

    private static Map<ExecutableElement, AnnotationValue> elementValues(AnnotationMirror mirror) {
        Map<ExecutableElement, AnnotationValue> result = new LinkedHashMap<>(mirror.getElementValues());
        for (Element enclosed : mirror.getAnnotationType().asElement().getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                if (enclosed instanceof ExecutableElement method
                        && !result.containsKey(method)
                        && method.getDefaultValue() != null) {
                    result.put(method, method.getDefaultValue());
                }
            }
        }
        return result;
    }

    private static Set<ConditionKey> readConditions(TypeElement type) {
        Set<ConditionKey> result = new LinkedHashSet<>();
        for (AnnotationMirror mirror : type.getAnnotationMirrors()) {
            String name = ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
            if (CONDITION_ON.equals(name)) {
                result.add(readCondition(mirror));
            } else if (CONDITION_ON_LIST.equals(name)) {
                for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : elementValues(mirror)
                        .entrySet()) {
                    if (entry.getKey().getSimpleName().toString().equals("value")) {
                        @SuppressWarnings("unchecked")
                        List<AnnotationValue> values = (List<AnnotationValue>) entry.getValue().getValue();
                        for (AnnotationValue value : values) {
                            if (value.getValue() instanceof AnnotationMirror nested) {
                                result.add(readCondition(nested));
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static ConditionKey readCondition(AnnotationMirror mirror) {
        String conditionClass = "";
        List<String> args = List.of();

        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : elementValues(mirror)
                .entrySet()) {
            String key = entry.getKey().getSimpleName().toString();
            if (key.equals("value")) {
                String name = typeName((TypeMirror) entry.getValue().getValue());
                if (name != null) {
                    conditionClass = name;
                }
            } else if (key.equals("args")) {
                @SuppressWarnings("unchecked")
                List<AnnotationValue> values = (List<AnnotationValue>) entry.getValue().getValue();
                List<String> parsed = new ArrayList<>();
                for (AnnotationValue value : values) {
                    parsed.add(String.valueOf(value.getValue()));
                }
                args = parsed;
            }
        }

        return new ConditionKey(conditionClass, args);
    }

    private void writeRegistry() {
        Filer filer = processingEnv.getFiler();

        try {
            JavaFileObject file = filer.createSourceFile("plugin.core.ComponentRegistry");
            try (Writer writer = file.openWriter()) {
                writer.write("package plugin.core;\n\n");
                writer.write("public final class ComponentRegistry {\n\n");
                writer.write("    public static final Class<?>[] COMPONENTS = {\n");

                for (String name : components.keySet()) {
                    writer.write("        " + name + ".class,\n");
                }

                writer.write("    };\n\n");
                writer.write("    private ComponentRegistry() {\n");
                writer.write("    }\n");
                writer.write("}\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate ComponentRegistry", e);
        }
    }
}
