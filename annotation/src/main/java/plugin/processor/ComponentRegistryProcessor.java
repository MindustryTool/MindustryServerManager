package plugin.processor;

import plugin.annotations.Component;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class ComponentRegistryProcessor extends AbstractProcessor {

    private final Set<String> components = new TreeSet<>();
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

        boolean found = false;
        for (Element element : roundEnv.getElementsAnnotatedWith(Component.class)) {
            if (element instanceof TypeElement typeElement && typeElement.getKind() != ElementKind.ANNOTATION_TYPE) {
                components.add(typeElement.getQualifiedName().toString());
                found = true;
            }
        }

        if (found) {
            writeRegistry();
            generated = true;
        }

        return false;
    }

    private void writeRegistry() {
        Filer filer = processingEnv.getFiler();

        try {
            JavaFileObject file = filer.createSourceFile("plugin.core.ComponentRegistry");
            try (Writer writer = file.openWriter()) {
                writer.write("package plugin.core;\n\n");
                writer.write("public final class ComponentRegistry {\n\n");
                writer.write("    public static final Class<?>[] COMPONENTS = {\n");

                List<String> names = new ArrayList<>(components);
                for (String name : names) {
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
