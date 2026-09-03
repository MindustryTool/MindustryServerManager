package plugin.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ExpUtilsTest {

    @Test
    void addExpIncreasesTotalExp() {
        float initialExp = 150f;
        float result = ExpUtils.calculateExp(initialExp, "+100");
        assertEquals(250f, result, 0.001f);
    }

    @Test
    void removeExpDecreasesTotalExp() {
        float initialExp = 150f;
        float result = ExpUtils.calculateExp(initialExp, "-50");
        assertEquals(100f, result, 0.001f);
    }

    @Test
    void removeExpClampsAtZero() {
        float initialExp = 150f;
        float result = ExpUtils.calculateExp(initialExp, "-300");
        assertEquals(0f, result, 0.001f);
    }

    @Test
    void setExpOverwritesTotalExp() {
        float initialExp = 150f;
        float result = ExpUtils.calculateExp(initialExp, "500");
        assertEquals(500f, result, 0.001f);

        float resultWithEquals = ExpUtils.calculateExp(initialExp, "=600");
        assertEquals(600f, resultWithEquals, 0.001f);

        float resultZero = ExpUtils.calculateExp(initialExp, "0");
        assertEquals(0f, resultZero, 0.001f);
    }

    @Test
    void addDecimalExp() {
        float initialExp = 100f;
        float result = ExpUtils.calculateExp(initialExp, "+10.5");
        assertEquals(110.5f, result, 0.001f);
    }

    @Test
    void addLevelUppercaseAndLowercase() {
        // Find exp for a known level
        int startLevel = 3;
        float currentExp = (float) ExpUtils.totalExpForLevel(startLevel);
        assertEquals(startLevel, ExpUtils.levelFromTotalExp((long) currentExp));

        float resultUpper = ExpUtils.calculateExp(currentExp, "+2L");
        int newLevelUpper = ExpUtils.levelFromTotalExp((long) resultUpper);
        assertEquals(5, newLevelUpper);
        assertEquals((float) ExpUtils.totalExpForLevel(5), resultUpper, 0.001f);

        float resultLower = ExpUtils.calculateExp(currentExp, "+2l");
        int newLevelLower = ExpUtils.levelFromTotalExp((long) resultLower);
        assertEquals(5, newLevelLower);
    }

    @Test
    void removeLevelDecreasesLevel() {
        int startLevel = 5;
        float currentExp = (float) ExpUtils.totalExpForLevel(startLevel);

        float resultUpper = ExpUtils.calculateExp(currentExp, "-2L");
        assertEquals(3, ExpUtils.levelFromTotalExp((long) resultUpper));
        assertEquals((float) ExpUtils.totalExpForLevel(3), resultUpper, 0.001f);

        float resultLower = ExpUtils.calculateExp(currentExp, "-1l");
        assertEquals(4, ExpUtils.levelFromTotalExp((long) resultLower));
    }

    @Test
    void removeLevelClampsAtLevelOne() {
        int startLevel = 3;
        float currentExp = (float) ExpUtils.totalExpForLevel(startLevel);

        float result = ExpUtils.calculateExp(currentExp, "-10L");
        assertEquals(1, ExpUtils.levelFromTotalExp((long) result));
        assertEquals(0f, result, 0.001f);
    }

    @Test
    void setLevelOverwritesLevel() {
        float currentExp = 50f;

        float result10L = ExpUtils.calculateExp(currentExp, "10L");
        assertEquals(10, ExpUtils.levelFromTotalExp((long) result10L));
        assertEquals((float) ExpUtils.totalExpForLevel(10), result10L, 0.001f);

        float result10l = ExpUtils.calculateExp(currentExp, "10l");
        assertEquals(10, ExpUtils.levelFromTotalExp((long) result10l));

        float resultEquals = ExpUtils.calculateExp(currentExp, "=8L");
        assertEquals(8, ExpUtils.levelFromTotalExp((long) resultEquals));

        float resultZero = ExpUtils.calculateExp(currentExp, "0L");
        assertEquals(1, ExpUtils.levelFromTotalExp((long) resultZero));
        assertEquals(0f, resultZero, 0.001f);
    }

    @Test
    void invalidFormatsThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, ""));
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, null));
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, "   "));
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, "+"));
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, "-"));
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, "+L"));
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, "-l"));
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, "abc"));
        assertThrows(IllegalArgumentException.class, () -> ExpUtils.calculateExp(100f, "1.5L"));
    }
}
