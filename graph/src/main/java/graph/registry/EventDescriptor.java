package graph.registry;

import java.util.List;
import java.util.Objects;

public record EventDescriptor(
        String id,
        String displayName,
        List<ParamDescriptor> payload,
        String category,
        String description) {

    public EventDescriptor {
        payload = List.copyOf(payload);
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Event id must not be blank");
        }
        displayName = displayName == null || displayName.isBlank()
                ? id : displayName;
        category = category == null ? "" : category;
        description = description == null ? "" : description;
    }
}
