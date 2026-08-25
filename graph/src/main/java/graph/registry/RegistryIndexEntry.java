package graph.registry;

import java.util.List;

public record RegistryIndexEntry(
        String kind,
        String id,
        String category,
        String ownerType,
        String summary) {

    public static final String KIND_FUNCTION = "function";
    public static final String KIND_PROPERTY = "property";
    public static final String KIND_EVENT = "event";
    public static final String KIND_CONSTRUCTOR = "constructor";

    public RegistryIndexEntry {
        kind = kind == null ? "" : kind;
        category = category == null ? "" : category;
        ownerType = ownerType == null ? "" : ownerType;
        summary = summary == null ? "" : summary;
    }

    public static RegistryIndexEntry function(String id, String category, String ownerType, String summary) {
        return new RegistryIndexEntry(KIND_FUNCTION, id, category, ownerType, summary);
    }

    public static RegistryIndexEntry event(String id, String category, String ownerType, String summary) {
        return new RegistryIndexEntry(KIND_EVENT, id, category, ownerType, summary);
    }

    public static List<String> kinds() {
        return List.of(KIND_FUNCTION, KIND_PROPERTY, KIND_EVENT, KIND_CONSTRUCTOR);
    }
}
