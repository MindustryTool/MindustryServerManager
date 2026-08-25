package graph.compile;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class CompilerService {

    public enum Engine {
        SYSTEM_TOOL_PROVIDER, ECJ_FALLBACK, NONE
    }

    public record CompiledClasses(String engine, String mainClassName,
                                  Map<String, byte[]> classes,
                                  List<String> errors) {

        public boolean ok() {
            return classes != null && !classes.isEmpty() && errors.isEmpty();
        }
    }

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "graph-compiler");
                thread.setDaemon(true);
                return thread;
            });

    public Engine availableEngine() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        return compiler != null
                ? Engine.SYSTEM_TOOL_PROVIDER
                : Engine.ECJ_FALLBACK;
    }

    public CompiledClasses compile(String className, String source) {
        try {
            return executor.submit(() -> compileOnCallerThread(className, source)).get();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return new CompiledClasses("none", className, null,
                    List.of("compilation interrupted: " + e.getMessage()));
        }
    }

    private CompiledClasses compileOnCallerThread(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return ecjFallback(className, source);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, null);
        try {
            String[] classpathEntries = System.getProperty("java.class.path", "")
                    .split(java.io.File.pathSeparator);
            try {
                fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH,
                        java.util.Arrays.stream(classpathEntries)
                                .filter(s -> !s.isBlank())
                                .map(java.io.File::new)
                                .toList());
            } catch (java.io.IOException e) {
                return new CompiledClasses(engineName(), className, null,
                        List.of("failed to configure compile classpath: " + e.getMessage()));
            }
            MemoryOutputManager manager = new MemoryOutputManager(fileManager);
            JavaFileObject unit = new SourceObject(className, source);
            JavaCompiler.CompilationTask task = compiler.getTask(null, manager,
                    diagnostics, List.of("-proc:none"), null, List.of(unit));
            Boolean success = task.call();
            if (success == null || !success || !manager.classes().containsKey(className)) {
                List<String> errors = new java.util.ArrayList<>();
                diagnostics.getDiagnostics().forEach(d ->
                        errors.add(String.valueOf(d.getMessage(null))));
                return new CompiledClasses(engineName(), className, null, errors);
            }
            return new CompiledClasses(engineName(), className,
                    new HashMap<>(manager.classes()), List.of());
        } finally {
            try {
                fileManager.close();
            } catch (Exception ignored) {
            }
        }
    }

    private CompiledClasses ecjFallback(String className, String source) {
        return new CompiledClasses("none", className, null,
                List.of("No Java compiler available on this runtime."
                        + " The bundled ECJ fallback is not wired yet;"
                        + " run on a JDK or provide a compiler."));
    }

    private static String engineName() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        return compiler != null ? "tool-provider" : "none";
    }

    private static final class SourceObject extends SimpleJavaFileObject {

        private final String source;

        SourceObject(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + ".java"),
                    Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class MemoryOutputManager
            extends javax.tools.ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, byte[]> classes = new HashMap<>();

        MemoryOutputManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        Map<String, byte[]> classes() {
            return classes;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind,
                                                   javax.tools.FileObject sibling) {
            return new SimpleJavaFileObject(
                    URI.create("mem:///" + className.replace('.', '/') + kind.extension),
                    kind) {
                @Override
                public OutputStream openOutputStream() {
                    return new ByteArrayOutputStream() {
                        @Override
                        public void close() {
                            classes.put(className, toByteArray());
                        }
                    };
                }
            };
        }
    }
}
