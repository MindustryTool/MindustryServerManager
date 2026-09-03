package plugin.core;

public interface RegistryLogger {
    void debug(String message, Object... args);
    void info(String message, Object... args);
    void error(String message, Throwable throwable, Object... args);
}
