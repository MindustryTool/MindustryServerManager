package plugin.utils;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeUtils {

    private static final Pattern PART = Pattern.compile("(\\d+)(ms|s|m|h|d)", Pattern.CASE_INSENSITIVE);

    public static String toString(Duration duration) {
        if (duration == null || duration.isZero()) {
            return "0ms";
        }

        long ms = duration.toMillis();
        StringBuilder out = new StringBuilder();

        long days = ms / 86_400_000L;
        ms %= 86_400_000L;

        long hours = ms / 3_600_000L;
        ms %= 3_600_000L;

        long minutes = ms / 60_000L;
        ms %= 60_000L;

        long seconds = ms / 1_000L;
        ms %= 1_000L;

        if (days > 0)
            out.append(days).append("d");
        if (hours > 0)
            out.append(hours).append("h");
        if (minutes > 0)
            out.append(minutes).append("m");
        if (seconds > 0)
            out.append(seconds).append("s");
        if (ms > 0)
            out.append(ms).append("ms");

        return out.toString();
    }

    public static String toSeconds(Duration duration) {
        if (duration == null || duration.isZero()) {
            return "0ms";
        }

        long ms = duration.toMillis();
        StringBuilder out = new StringBuilder();

        long days = ms / 86_400_000L;
        ms %= 86_400_000L;

        long hours = ms / 3_600_000L;
        ms %= 3_600_000L;

        long minutes = ms / 60_000L;
        ms %= 60_000L;

        long seconds = ms / 1_000L;
        ms %= 1_000L;

        if (days > 0)
            out.append(days).append("d");
        if (hours > 0)
            out.append(hours).append("h");
        if (minutes > 0)
            out.append(minutes).append("m");
        if (seconds > 0)
            out.append(seconds).append("s");

        return out.toString();
    }

    public static Duration parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Time string is empty");
        }

        Matcher matcher = PART.matcher(input.replaceAll("\\s+", ""));
        long totalMillis = 0;
        boolean found = false;

        while (matcher.find()) {
            found = true;

            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2).toLowerCase();

            switch (unit) {
                case "ms":
                    totalMillis += value;
                    break;
                case "s":
                    totalMillis += value * 1_000L;
                    break;
                case "m":
                    totalMillis += value * 60_000L;
                    break;
                case "h":
                    totalMillis += value * 3_600_000L;
                    break;
                case "d":
                    totalMillis += value * 86_400_000L;
                    break;
                default:
                    throw new IllegalStateException("Unknown unit: " + unit);
            }
        }

        if (!found) {
            throw new IllegalArgumentException("Invalid time format: " + input);
        }

        return Duration.ofMillis(totalMillis);
    }
}
