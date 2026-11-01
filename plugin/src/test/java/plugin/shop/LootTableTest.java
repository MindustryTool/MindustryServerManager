package plugin.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

public class LootTableTest {

    @Test
    void emptyTableThrowsException() {
        LootTable<String> table = new LootTable<>();
        assertThrows(IllegalStateException.class, () -> table.roll(new Random()));
    }

    @Test
    void singleItemAlwaysRolls() {
        LootTable<String> table = new LootTable<>();
        table.add(10.0, "gem");

        Random random = new Random(42);
        for (int i = 0; i < 50; i++) {
            assertEquals("gem", table.roll(random));
        }
    }

    @Test
    void invalidWeightThrowsException() {
        LootTable<String> table = new LootTable<>();
        assertThrows(IllegalArgumentException.class, () -> table.add(0, "bad"));
        assertThrows(IllegalArgumentException.class, () -> table.add(-5.0, "bad"));
    }

    @Test
    void nullValueThrowsException() {
        LootTable<String> table = new LootTable<>();
        assertThrows(IllegalArgumentException.class, () -> table.add(1.0, null));
    }

    @Test
    void weightedDistributionMatchesChances() {
        LootTable<String> table = new LootTable<>();
        table.add(80.0, "common");
        table.add(20.0, "rare");

        Random random = new Random(12345);
        int commonCount = 0;
        int rareCount = 0;
        int total = 10_000;

        for (int i = 0; i < total; i++) {
            String result = table.roll(random);
            if ("common".equals(result)) {
                commonCount++;
            } else if ("rare".equals(result)) {
                rareCount++;
            }
        }

        assertTrue(commonCount > 7500 && commonCount < 8500, "Common should be ~80%, got " + commonCount);
        assertTrue(rareCount > 1500 && rareCount < 2500, "Rare should be ~20%, got " + rareCount);
    }
}
