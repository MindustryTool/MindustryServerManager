package graph.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TypeRef {

    private static final List<String> PRIMITIVE_NAMES =
            List.of("String", "Int", "Long", "Float", "Double", "Boolean", "Byte");

    private static final List<String> ARITY_ONE_CONTAINERS = List.of("List", "Set", "Optional", "Future");

    public static final TypeRef STRING = of("String");
    public static final TypeRef INT = of("Int");
    public static final TypeRef LONG = of("Long");
    public static final TypeRef FLOAT = of("Float");
    public static final TypeRef DOUBLE = of("Double");
    public static final TypeRef BOOLEAN = of("Boolean");
    public static final TypeRef BYTE = of("Byte");

    private final String base;
    private final List<TypeRef> params;
    private final boolean nullable;

    private TypeRef(String base, List<TypeRef> params, boolean nullable) {
        this.base = Objects.requireNonNull(base, "base");
        if (base.isEmpty() || !Character.isJavaIdentifierStart(base.charAt(0))) {
            throw new IllegalArgumentException("Invalid type base name: " + base);
        }
        for (int i = 1; i < base.length(); i++) {
            if (!Character.isJavaIdentifierPart(base.charAt(i))) {
                throw new IllegalArgumentException("Invalid type base name: " + base);
            }
        }
        if (!params.isEmpty()) {
            if (ARITY_ONE_CONTAINERS.contains(base) && params.size() != 1) {
                throw new IllegalArgumentException(
                        base + " requires exactly 1 type parameter, got " + params.size());
            }
            if (base.equals("Map") && params.size() != 2) {
                throw new IllegalArgumentException(
                        "Map requires exactly 2 type parameters, got " + params.size());
            }
        }
        this.params = List.copyOf(params);
        this.nullable = nullable;
    }

    public static TypeRef of(String base) {
        return new TypeRef(base, List.of(), false);
    }

    public static TypeRef list(TypeRef element) {
        return new TypeRef("List", List.of(Objects.requireNonNull(element)), false);
    }

    public static TypeRef set(TypeRef element) {
        return new TypeRef("Set", List.of(Objects.requireNonNull(element)), false);
    }

    public static TypeRef map(TypeRef key, TypeRef value) {
        return new TypeRef("Map", List.of(
                Objects.requireNonNull(key), Objects.requireNonNull(value)), false);
    }

    public static TypeRef optional(TypeRef element) {
        return new TypeRef("Optional", List.of(Objects.requireNonNull(element)), false);
    }

    public static TypeRef future(TypeRef element) {
        return new TypeRef("Future", List.of(Objects.requireNonNull(element)), false);
    }

    public TypeRef asNullable() {
        return nullable ? this : new TypeRef(base, params, true);
    }

    public TypeRef asNonNull() {
        return nullable ? new TypeRef(base, params, false) : this;
    }

    public String base() {
        return base;
    }

    public List<TypeRef> params() {
        return params;
    }

    public boolean isNullable() {
        return nullable;
    }

    public boolean isPrimitive() {
        return params.isEmpty() && PRIMITIVE_NAMES.contains(base);
    }

    public boolean isGenericContainer() {
        return !params.isEmpty();
    }

    public boolean isList() {
        return base.equals("List") && params.size() == 1;
    }

    public boolean isSet() {
        return base.equals("Set") && params.size() == 1;
    }

    public boolean isMap() {
        return base.equals("Map") && params.size() == 2;
    }

    public boolean isOptional() {
        return base.equals("Optional") && params.size() == 1;
    }

    public boolean isFuture() {
        return base.equals("Future") && params.size() == 1;
    }

    public static TypeRef parse(String text) {
        Parser parser = new Parser(text);
        TypeRef result = parser.parseType();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected trailing input");
        }
        return result;
    }

    public String print() {
        StringBuilder sb = new StringBuilder();
        printTo(sb);
        return sb.toString();
    }

    private void printTo(StringBuilder sb) {
        sb.append(base);
        if (!params.isEmpty()) {
            sb.append('<');
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                params.get(i).printTo(sb);
            }
            sb.append('>');
        }
        if (nullable) {
            sb.append('?');
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TypeRef other)) {
            return false;
        }
        return nullable == other.nullable
                && base.equals(other.base)
                && params.equals(other.params);
    }

    @Override
    public int hashCode() {
        return Objects.hash(base, params, nullable);
    }

    @Override
    public String toString() {
        return print();
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + pos + " in '" + text + "'");
        }

        char peek() {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            return text.charAt(pos);
        }

        void expect(char c) {
            if (atEnd() || text.charAt(pos) != c) {
                throw error("Expected '" + c + "'");
            }
            pos++;
        }

        TypeRef parseType() {
            skipWhitespace();
            String name = parseName();
            skipWhitespace();
            List<TypeRef> args = List.of();
            if (!atEnd() && peek() == '<') {
                pos++;
                args = parseArgs();
                expect('>');
                skipWhitespace();
            }
            boolean nullable = false;
            if (!atEnd() && peek() == '?') {
                pos++;
                nullable = true;
                skipWhitespace();
            }
            return new TypeRef(name, args, nullable);
        }

        private String parseName() {
            if (atEnd()) {
                throw error("Expected type name");
            }
            char first = peek();
            if (!Character.isJavaIdentifierStart(first)) {
                throw error("Expected type name");
            }
            int start = pos;
            pos++;
            while (!atEnd() && Character.isJavaIdentifierPart(text.charAt(pos))) {
                pos++;
            }
            return text.substring(start, pos);
        }

        private List<TypeRef> parseArgs() {
            List<TypeRef> args = new ArrayList<>();
            args.add(parseType());
            skipWhitespace();
            while (!atEnd() && peek() == ',') {
                pos++;
                args.add(parseType());
                skipWhitespace();
            }
            return args;
        }
    }
}
