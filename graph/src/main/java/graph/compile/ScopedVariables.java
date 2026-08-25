package graph.compile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ScopedVariables {

    public static final String LOCAL = "LOCAL";
    public static final String GRAPH = "GRAPH";
    public static final String SERVER = "SERVER";
    public static final String PLAYER = "PLAYER";
    public static final String TEAM = "TEAM";
    public static final String WORLD = "WORLD";

    public static final Set<String> SCOPES =
            Set.of(LOCAL, GRAPH, SERVER, PLAYER, TEAM, WORLD);

    private final Map<String, Object> graphLevel = new ConcurrentHashMap<>();
    private final Map<String, Object> serverLevel = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> playerLevel = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> teamLevel = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> worldLevel = new ConcurrentHashMap<>();

    public Object get(String scope, String scopeKey, String name) {
        return level(scope, scopeKey).get(name);
    }

    public void set(String scope, String scopeKey, String name, Object value) {
        level(scope, scopeKey).put(name, value);
    }

    public void clearKey(String scope, String scopeKey) {
        level(scope, scopeKey).clear();
    }

    public int sizeOf(String scope, String scopeKey) {
        return level(scope, scopeKey).size();
    }

    private Map<String, Object> level(String scope, String scopeKey) {
        switch (scope == null ? GRAPH : scope) {
            case SERVER:
                return serverLevel;
            case PLAYER:
                return keyed(playerLevel, scopeKey);
            case TEAM:
                return keyed(teamLevel, scopeKey);
            case WORLD:
                return keyed(worldLevel, scopeKey);
            case GRAPH:
            default:
                return graphLevel;
        }
    }

    private static Map<String, Object> keyed(Map<String, Map<String, Object>> backing,
                                             String key) {
        return backing.computeIfAbsent(
                key == null || key.isEmpty() ? "__global__" : key,
                k -> new ConcurrentHashMap<>());
    }
}
