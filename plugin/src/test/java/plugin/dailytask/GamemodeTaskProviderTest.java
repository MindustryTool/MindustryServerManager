package plugin.dailytask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import arc.Core;
import arc.Settings;
import arc.files.Fi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.gamemode.Gamemode;

public class GamemodeTaskProviderTest {

    @TempDir
    static Path settingsDir;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @AfterEach
    void tearDown() {
        Core.settings.remove(Gamemode.KEY);
    }

    @Test
    void defaultTaskProviderAlwaysSupports() {
        DefaultTaskProvider provider = new DefaultTaskProvider();
        assertTrue(provider.supports());
        List<DailyTask> tasks = provider.getTasks();
        assertEquals(3, tasks.size());
        assertTrue(tasks.stream().allMatch(t -> t.getGoal() > 0 && t.getRewardCoins() > 0));
    }

    @Test
    void towerDefenseTaskProviderSupportsWhenActive() {
        TowerDefenseTaskProvider provider = new TowerDefenseTaskProvider();
        assertFalse(provider.supports());

        Gamemode.set("TowerDefense");
        assertTrue(provider.supports());

        List<DailyTask> tasks = provider.getTasks();
        assertEquals(3, tasks.size());
        assertTrue(tasks.stream().anyMatch(t -> t.getId().equals("td_waves")));
    }

    @Test
    void floodTaskProviderSupportsWhenActive() {
        FloodTaskProvider provider = new FloodTaskProvider();
        assertFalse(provider.supports());

        Gamemode.set("Flood");
        assertTrue(provider.supports());

        List<DailyTask> tasks = provider.getTasks();
        assertEquals(3, tasks.size());
        assertTrue(tasks.stream().anyMatch(t -> t.getId().equals("flood_waves")));
    }

    @Test
    void cataliTaskProviderSupportsWhenActive() {
        CataliTaskProvider provider = new CataliTaskProvider();
        assertFalse(provider.supports());

        Gamemode.set("Catali");
        assertTrue(provider.supports());

        List<DailyTask> tasks = provider.getTasks();
        assertEquals(3, tasks.size());
        assertTrue(tasks.stream().anyMatch(t -> t.getId().equals("catali_kills")));
    }
}
