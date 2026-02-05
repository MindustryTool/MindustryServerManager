package plugin.utils;

import mindustry.Vars;
import mindustry.content.UnitTypes;
import plugin.type.Session;
import plugin.type.SessionData;

public class ExpUtils {

    public static long getLevel(Session session) {
        return levelFromTotalExp(getTotalExp(session.data(), session.sessionPlayTime()));
    }

    public static long getTotalExp(SessionData data, long sessionPlayTime) {
        long exp = unitHealthToExp(data.kills.entrySet().stream().mapToDouble(entry -> {
            var unit = Vars.content.unit(entry.getKey());

            return unit.health * entry.getValue();
        }).sum());

        exp += playTimeToExp(data.playTime + sessionPlayTime);
        return exp;
    }

    public static long unitHealthToExp(double health) {
        return (long) (health / UnitTypes.flare.health / 8);
    }

    // Killing 1 flare = 5 seconds of play time
    public static long playTimeToExp(long playTime) {
        return (long) (playTime / UnitTypes.flare.health / 5 / 8);
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

}
