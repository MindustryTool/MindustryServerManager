package graph.compile;

import java.io.InputStream;
import java.util.Map;

public final class GenerationClassLoader extends ClassLoader {

    private final Map<String, byte[]> classes;
    private final java.util.concurrent.atomic.AtomicLong liveUsers =
            new java.util.concurrent.atomic.AtomicLong(1);
    private volatile boolean retired;

    public GenerationClassLoader(Map<String, byte[]> classes, ClassLoader abiParent) {
        super(abiParent);
        this.classes = Map.copyOf(classes);
    }

    public void trackLiveUsage() {
        liveUsers.incrementAndGet();
    }

    public void releaseLiveUsage() {
        liveUsers.decrementAndGet();
    }

    public boolean hasLiveUsers() {
        return liveUsers.get() > 0;
    }

    public synchronized void retire() {
        retired = true;
        liveUsers.set(0);
    }

    public boolean isRetired() {
        return retired;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (retired) {
            throw new IllegalStateException(
                    "GenerationClassLoader retired for " + name);
        }
        byte[] bytes = classes.get(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (!retired && name.endsWith(".class")) {
            String candidate = name.substring(0, name.length() - ".class".length())
                    .replace('/', '.');
            byte[] bytes = classes.get(candidate);
            if (bytes != null) {
                return new java.io.ByteArrayInputStream(bytes);
            }
        }
        return super.getResourceAsStream(name);
    }

    public Class<?> loadMain(String mainClassName) throws ClassNotFoundException {
        Class<?> loaded = super.loadClass(mainClassName);
        if (loaded.getClassLoader() != this) {
            throw new IllegalStateException(
                    "Main class resolved outside generation loader: " + mainClassName);
        }
        return loaded;
    }
}
