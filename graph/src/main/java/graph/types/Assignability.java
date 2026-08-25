package graph.types;

import java.util.List;
import java.util.Objects;

public final class Assignability {

    public static final String EXEC_BASE = "exec";

    private static final List<String> NUMERIC_ORDER =
            List.of("Byte", "Int", "Long", "Float", "Double");

    private Assignability() {
    }

    public enum Relation {
        IDENTICAL,
        NULLABILITY_MISMATCH,
        WIDENING,
        STRING_TO_NUMBER,
        NUMBER_TO_STRING,
        COVARIANT_CONTAINER,
        EXEC,
        NONE
    }

    public static boolean isAssignable(TypeRef source, TypeRef target) {
        Relation relation = relation(source, target);
        return relation != Relation.NONE && relation != Relation.NULLABILITY_MISMATCH;
    }

    public static boolean isExec(TypeRef type) {
        return type.base().equals(EXEC_BASE);
    }

    public static Relation relation(TypeRef source, TypeRef target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        if (isExec(source) && isExec(target)) {
            return Relation.EXEC;
        }
        if (isExec(source) || isExec(target)) {
            return Relation.NONE;
        }

        if (source.equals(target)) {
            return Relation.IDENTICAL;
        }

        boolean nullabilityOk = !source.isNullable() || target.isNullable();
        if (structuralMatchIgnoringNullability(source, target)) {
            return nullabilityOk ? Relation.IDENTICAL : Relation.NULLABILITY_MISMATCH;
        }

        if (!nullabilityOk) {
            return Relation.NONE;
        }

        if (source.isPrimitive() && target.isPrimitive()) {
            int from = NUMERIC_ORDER.indexOf(source.base());
            int to = NUMERIC_ORDER.indexOf(target.base());
            if (from >= 0 && to > from) {
                return Relation.WIDENING;
            }
            if (source.base().equals("String") || target.base().equals("String")) {
                return source.base().equals("String")
                        ? Relation.STRING_TO_NUMBER
                        : Relation.NUMBER_TO_STRING;
            }
            return Relation.NONE;
        }

        if (source.base().equals("String") && isParseableNumberOrBool(target)) {
            return Relation.STRING_TO_NUMBER;
        }
        if (target.base().equals("String") && (source.isPrimitive())) {
            return Relation.NUMBER_TO_STRING;
        }

        if (sameContainerBase(source, target)
                && source.params().size() == target.params().size()
                && !source.params().isEmpty()
                && nullabilityOk) {
            boolean allCovariant = true;
            for (int i = 0; i < source.params().size(); i++) {
                if (!isAssignable(source.params().get(i), target.params().get(i))) {
                    allCovariant = false;
                    break;
                }
            }
            if (allCovariant) {
                return Relation.COVARIANT_CONTAINER;
            }
        }

        return Relation.NONE;
    }

    private static boolean structuralMatchIgnoringNullability(TypeRef source, TypeRef target) {
        return source.asNonNull().equals(target.asNonNull());
    }

    private static boolean isParseableNumberOrBool(TypeRef target) {
        return target.isPrimitive();
    }

    private static boolean sameContainerBase(TypeRef a, TypeRef b) {
        return switch (a.base()) {
            case "List", "Set", "Map", "Optional", "Future" -> a.base().equals(b.base());
            default -> false;
        };
    }
}
