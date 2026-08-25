package graph.registry;

import java.util.List;
import java.util.Objects;

public record FunctionDescriptor(
        String id,
        String displayName,
        String category,
        String ownerType,
        List<Overload> overloads,
        ThreadRequirement threadRequirement,
        boolean codegenSafe,
        boolean advanced,
        boolean deprecated,
        String sinceVersion,
        String description,
        List<String> aliases) {

    public FunctionDescriptor {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Function id must not be blank");
        }
        if (!id.matches("[a-z0-9][a-z0-9.\\-_]*")) {
            throw new IllegalArgumentException("Function id must be dot-separated lowercase: " + id);
        }
        overloads = List.copyOf(overloads);
        if (overloads.isEmpty()) {
            throw new IllegalArgumentException("Function '" + id + "' requires at least one overload");
        }
        aliases = List.copyOf(aliases);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String displayName;
        private String category = "";
        private String ownerType = "";
        private final List<Overload> overloads = new java.util.ArrayList<>();
        private ThreadRequirement threadRequirement = ThreadRequirement.MAIN_THREAD;
        private boolean codegenSafe = true;
        private boolean advanced;
        private boolean deprecated;
        private String sinceVersion = "";
        private String description = "";
        private final List<String> aliases = new java.util.ArrayList<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder displayName(String value) {
            this.displayName = value;
            return this;
        }

        public Builder category(String value) {
            this.category = value;
            return this;
        }

        public Builder ownerType(String value) {
            this.ownerType = value;
            return this;
        }

        public Builder overload(Overload overload) {
            this.overloads.add(overload);
            return this;
        }

        public Builder threadRequirement(ThreadRequirement value) {
            this.threadRequirement = value;
            return this;
        }

        public Builder codegenSafe(boolean value) {
            this.codegenSafe = value;
            return this;
        }

        public Builder advanced(boolean value) {
            this.advanced = value;
            return this;
        }

        public Builder deprecated(boolean value) {
            this.deprecated = value;
            return this;
        }

        public Builder sinceVersion(String value) {
            this.sinceVersion = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder alias(String value) {
            this.aliases.add(value);
            return this;
        }

        public FunctionDescriptor build() {
            String name = displayName != null ? displayName : id;
            int lastDot = id.lastIndexOf('.');
            if (displayName == null && lastDot >= 0 && lastDot < id.length() - 1) {
                name = id.substring(lastDot + 1);
            }
            return new FunctionDescriptor(id, name, category, ownerType, overloads,
                    threadRequirement, codegenSafe, advanced, deprecated, sinceVersion,
                    description, aliases);
        }
    }
}
