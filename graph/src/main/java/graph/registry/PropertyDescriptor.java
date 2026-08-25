package graph.registry;

import java.util.Objects;

import graph.types.TypeRef;

public record PropertyDescriptor(
        String id,
        String property,
        TypeRef ownerType,
        TypeRef valueType,
        boolean writable,
        ThreadRequirement threadRequirement,
        String description) {

    public PropertyDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(property, "property");
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(valueType, "valueType");
        Objects.requireNonNull(threadRequirement, "threadRequirement");
        Objects.requireNonNull(description, "description");
        if (!ParamDescriptor.PortNames.isValid(property)) {
            throw new IllegalArgumentException("Invalid property name: '" + property + "'");
        }
    }
}
