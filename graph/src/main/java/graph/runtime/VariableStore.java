package graph.runtime;

import java.util.HashMap;
import java.util.Map;

public final class VariableStore {

    private final Map<String, Object> values = new HashMap<>();

    public synchronized Object get(String variable) {
        return values.get(variable);
    }

    public synchronized void set(String variable, Object value) {
        values.put(variable, value);
    }

    public synchronized Map<String, Object> snapshot() {
        return new HashMap<>(values);
    }
}
