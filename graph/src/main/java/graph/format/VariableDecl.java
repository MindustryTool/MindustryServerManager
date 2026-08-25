package graph.format;

import java.util.Objects;

public record VariableDecl(String name, String scope, String typeRef) {

    public VariableDecl {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(typeRef, "typeRef");
        if (!PortAddress.isIdentifier(name)) {
            throw new IllegalArgumentException("Invalid variable name: '" + name + "'");
        }
        scope = VariableScope.normalize(scope);
    }
}
