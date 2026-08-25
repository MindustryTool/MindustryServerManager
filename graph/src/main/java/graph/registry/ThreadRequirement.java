package graph.registry;

public enum ThreadRequirement {
    MAIN_THREAD,
    ASYNC,
    PURE,
    READ_ONLY,
    UNSAFE;

    public static ThreadRequirement parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Thread requirement must not be empty");
        }
        try {
            return valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown thread requirement '" + text + "', expected one of " + names());
        }
    }

    public static boolean isValid(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String names() {
        StringBuilder sb = new StringBuilder("[");
        ThreadRequirement[] values = values();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values[i].name());
        }
        return sb.append(']').toString();
    }

    public boolean runsOnMainThread() {
        return this == MAIN_THREAD || this == PURE || this == READ_ONLY;
    }
}
