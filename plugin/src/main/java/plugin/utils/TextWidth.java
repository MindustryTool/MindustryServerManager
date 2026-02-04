package plugin.utils;

public final class TextWidth {

    public static int measure(String text) {
        if (text == null || text.isEmpty())
            return 0;

        int width = 0;

        for (int i = 0, len = text.length(); i < len; i++) {
            char c = text.charAt(i);

            // ASCII fast path
            if (c <= 127) {
                width += asciiWidth(c);
            } else {
                // Unicode fallback (CJK, symbols, etc.)
                width += unicodeWidth(c);
            }
        }

        return width;
    }

    private static int asciiWidth(char c) {
        return switch (c) {

            // ---- very narrow ----
            case 'I', 'i', 'l', '!', '|', '\'' -> 3;

            // ---- narrow ----
            case '.', ',', ':', ';', '`' -> 3;
            case '(', ')', '[', ']', '{', '}' -> 4;

            // ---- whitespace ----
            case ' ' -> 4;
            case '\t' -> 16;

            // ---- digits ----
            case '0', '1', '2', '3', '4',
                    '5', '6', '7', '8', '9' ->
                6;

            // ---- medium letters ----
            case 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
                    'j', 'k', 'n', 'o', 'p', 'q', 'r', 's',
                    't', 'u', 'v', 'x', 'y', 'z' ->
                7;

            // ---- wide letters ----
            case 'm', 'w' -> 9;

            // ---- uppercase ----
            case 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H',
                    'J', 'K', 'L', 'N', 'O', 'P', 'Q', 'R',
                    'S', 'T', 'U', 'V', 'X', 'Y', 'Z' ->
                8;

            case 'M', 'W' -> 10;

            // ---- symbols ----
            case '-', '_', '+', '=', '~' -> 6;
            case '/', '\\' -> 5;
            case '*', '^' -> 6;
            case '@', '#', '$', '%', '&' -> 8;

            // ---- newline ----
            case '\n' -> 0;

            // ---- fallback ----
            default -> 7;
        };
    }

    private static int unicodeWidth(char c) {
        // CJK characters are visually wide
        if (c >= 0x4E00 && c <= 0x9FFF || // CJK Unified Ideographs
                c >= 0x3040 && c <= 0x30FF || // Japanese
                c >= 0xAC00 && c <= 0xD7AF // Korean
        ) {
            return 14;
        }

        // Emoji / symbols
        if (c >= 0x1F300 && c <= 0x1FAFF) {
            return 16;
        }

        return 8;
    }
}
