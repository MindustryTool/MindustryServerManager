package graph.registry;

import java.util.Objects;

import graph.types.TypeRef;

public record ParamDescriptor(String name, TypeRef type) {

    public ParamDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (!PortNames.isValid(name)) {
            throw new IllegalArgumentException("Invalid parameter/port name: '" + name + "'");
        }
    }

    public static final class PortNames {
        private PortNames() {
        }

        public static boolean isValid(String text) {
            if (text == null || text.isEmpty()) {
                return false;
            }
            if (!Character.isJavaIdentifierStart(text.charAt(0))) {
                return false;
            }
            for (int i = 1; i < text.length(); i++) {
                if (!Character.isJavaIdentifierPart(text.charAt(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}
