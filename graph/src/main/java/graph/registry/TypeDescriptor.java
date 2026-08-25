package graph.registry;

import java.util.Objects;

public record TypeDescriptor(String baseName, String kind, String description) {

    public TypeDescriptor {
        Objects.requireNonNull(baseName, "baseName");
        Objects.requireNonNull(kind, "kind");
        description = description == null ? "" : description;
        if (!baseName.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid type base name: " + baseName);
        }
    }

    public static TypeDescriptor mindustry(String baseName, String description) {
        return new TypeDescriptor(baseName, "mindustry", description);
    }
}
