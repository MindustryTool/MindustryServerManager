package plugin.session;

public class ExpUtils {

    public static long getLevel(Session session) {
        return levelFromTotalExp((long) session.getData().exp);
    }

    public static int levelFromTotalExp(long totalExp) {
        if (totalExp <= 0)
            return 1;

        double a = 0.08;
        double b = 0.004;

        double raw = Math.cbrt(totalExp * b) + Math.sqrt(totalExp * a);
        return Math.max(1, (int) Math.floor(raw));
    }

    public static long totalExpForLevel(int targetLevel) {
        if (targetLevel <= 1)
            return 0;

        long low = 0;
        long high = 1;

        // expand upper bound until it reaches the target level
        while (levelFromTotalExp(high) < targetLevel) {
            high *= 2;
        }

        // binary search exact boundary
        while (low < high) {
            long mid = (low + high) >>> 1;

            if (levelFromTotalExp(mid) < targetLevel) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }

        return low;
    }

    public static long excessExp(long totalExp) {
        int level = levelFromTotalExp(totalExp);
        long levelStartExp = totalExpForLevel(level);
        return Math.max(0, totalExp - levelStartExp);
    }

    public static long expCapOfLevel(int level) {
        return totalExpForLevel(level + 1) - totalExpForLevel(level);
    }

    public static float calculateExp(float currentExp, String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Amount cannot be empty");
        }

        String raw = input.trim();
        boolean isLevel = raw.endsWith("l") || raw.endsWith("L");
        String numPart = isLevel ? raw.substring(0, raw.length() - 1).trim() : raw;

        char op = '=';
        if (numPart.startsWith("+")) {
            op = '+';
            numPart = numPart.substring(1).trim();
        } else if (numPart.startsWith("-")) {
            op = '-';
            numPart = numPart.substring(1).trim();
        } else if (numPart.startsWith("=")) {
            op = '=';
            numPart = numPart.substring(1).trim();
        }

        if (numPart.isEmpty()) {
            throw new IllegalArgumentException("Missing number in amount: " + input);
        }

        if (isLevel) {
            int levelVal;
            try {
                levelVal = Integer.parseInt(numPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid level value: " + numPart);
            }

            if (levelVal < 0) {
                throw new IllegalArgumentException("Level value cannot be negative: " + numPart);
            }

            int curLevel = levelFromTotalExp((long) currentExp);
            int targetLevel;
            if (op == '+') {
                targetLevel = curLevel + levelVal;
            } else if (op == '-') {
                targetLevel = Math.max(1, curLevel - levelVal);
            } else {
                targetLevel = Math.max(1, levelVal);
            }

            return (float) totalExpForLevel(targetLevel);
        } else {
            double expVal;
            try {
                expVal = Double.parseDouble(numPart);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid exp value: " + numPart);
            }

            if (Double.isNaN(expVal) || Double.isInfinite(expVal) || expVal < 0) {
                throw new IllegalArgumentException("Exp value must be a valid non-negative number: " + numPart);
            }

            float result;
            if (op == '+') {
                result = currentExp + (float) expVal;
            } else if (op == '-') {
                result = Math.max(0f, currentExp - (float) expVal);
            } else {
                result = Math.max(0f, (float) expVal);
            }

            return result;
        }
    }
}
