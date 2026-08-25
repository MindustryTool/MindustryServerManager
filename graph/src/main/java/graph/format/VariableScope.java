package graph.format;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class VariableScope {

    public static final String LOCAL = "LOCAL";
    public static final String GRAPH = "GRAPH";
    public static final String SERVER = "SERVER";
    public static final String PLAYER = "PLAYER";
    public static final String TEAM = "TEAM";
    public static final String WORLD = "WORLD";

    public static final Set<String> ALL =
            Set.of(LOCAL, GRAPH, SERVER, PLAYER, TEAM, WORLD);

    private VariableScope() {
    }

    public static boolean isValid(String scope) {
        return scope != null && ALL.contains(scope.toUpperCase(Locale.ROOT));
    }

    public static String normalize(String scope) {
        return Objects.requireNonNull(scope, "scope").toUpperCase(Locale.ROOT);
    }

    public static List<String> all() {
        return List.of(LOCAL, GRAPH, SERVER, PLAYER, TEAM, WORLD);
    }

    public static boolean requiresKey(String scope) {
        String normalized = normalize(scope);
        return normalized.equals(PLAYER) || normalized.equals(TEAM) || normalized.equals(WORLD);
    }
}
