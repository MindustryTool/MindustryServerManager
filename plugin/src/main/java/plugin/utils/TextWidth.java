package plugin.utils;

import java.util.HashMap;
import java.util.Map;

import plugin.PluginEvents;
import plugin.event.PluginUnloadEvent;

public final class TextWidth {

    public static final Map<Character, Integer> WIDTH = new HashMap<>();

    static {
        // Control chars
        for (char c = 0; c < 32; c++)
            WIDTH.put(c, 0);

        WIDTH.put((char) 127, 0);

        WIDTH.put(' ', 5);
        WIDTH.put('!', 2);
        WIDTH.put('"', 6);
        WIDTH.put('#', 11);
        WIDTH.put('$', 7);
        WIDTH.put('%', 12);
        WIDTH.put('&', 9);
        WIDTH.put('\'', 3);
        WIDTH.put('(', 6);
        WIDTH.put(')', 6);
        WIDTH.put('*', 8);
        WIDTH.put('+', 7);
        WIDTH.put(',', 3);
        WIDTH.put('-', 7);
        WIDTH.put('.', 3);
        WIDTH.put('/', 10);

        // digits
        WIDTH.put('0', 12);
        WIDTH.put('1', 4);
        WIDTH.put('2', 10);
        WIDTH.put('3', 9);
        WIDTH.put('4', 10);
        WIDTH.put('5', 10);
        WIDTH.put('6', 10);
        WIDTH.put('7', 9);
        WIDTH.put('8', 10);
        WIDTH.put('9', 10);

        WIDTH.put(':', 3);
        WIDTH.put(';', 3);
        WIDTH.put('<', 8);
        WIDTH.put('=', 7);
        WIDTH.put('>', 8);
        WIDTH.put('?', 9);
        WIDTH.put('@', 9);

        // uppercase
        WIDTH.put('A', 11);
        WIDTH.put('B', 11);
        WIDTH.put('C', 10);
        WIDTH.put('D', 11);
        WIDTH.put('E', 10);
        WIDTH.put('F', 10);
        WIDTH.put('G', 11);
        WIDTH.put('H', 11);
        WIDTH.put('I', 2);
        WIDTH.put('J', 10);
        WIDTH.put('K', 11);
        WIDTH.put('L', 10);
        WIDTH.put('M', 12);
        WIDTH.put('N', 11);
        WIDTH.put('O', 11);
        WIDTH.put('P', 11);
        WIDTH.put('Q', 11);
        WIDTH.put('R', 12);
        WIDTH.put('S', 10);
        WIDTH.put('T', 10);
        WIDTH.put('U', 11);
        WIDTH.put('V', 13);
        WIDTH.put('W', 15);
        WIDTH.put('X', 12);
        WIDTH.put('Y', 11);
        WIDTH.put('Z', 11);

        WIDTH.put('[', 4);
        WIDTH.put('\\', 10);
        WIDTH.put(']', 4);
        WIDTH.put('^', 6);
        WIDTH.put('_', 8);
        WIDTH.put('`', 5);

        // lowercase
        WIDTH.put('a', 9);
        WIDTH.put('b', 9);
        WIDTH.put('c', 8);
        WIDTH.put('d', 9);
        WIDTH.put('e', 9);
        WIDTH.put('f', 7);
        WIDTH.put('g', 9);
        WIDTH.put('h', 9);
        WIDTH.put('i', 2);
        WIDTH.put('j', 6);
        WIDTH.put('k', 10);
        WIDTH.put('l', 4);
        WIDTH.put('m', 11);
        WIDTH.put('n', 9);
        WIDTH.put('o', 9);
        WIDTH.put('p', 9);
        WIDTH.put('q', 9);
        WIDTH.put('r', 7);
        WIDTH.put('s', 8);
        WIDTH.put('t', 8);
        WIDTH.put('u', 9);
        WIDTH.put('v', 10);
        WIDTH.put('w', 14);
        WIDTH.put('x', 11);
        WIDTH.put('y', 9);
        WIDTH.put('z', 8);

        WIDTH.put('{', 6);
        WIDTH.put('|', 2);
        WIDTH.put('}', 6);
        WIDTH.put('~', 8);

        PluginEvents.run(PluginUnloadEvent.class, () -> {
            WIDTH.clear();
        });
    }

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
        return WIDTH.getOrDefault(c, 8);
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
