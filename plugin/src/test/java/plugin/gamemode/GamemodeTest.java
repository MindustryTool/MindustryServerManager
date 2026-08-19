package plugin.gamemode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import arc.Core;
import arc.Settings;
import arc.files.Fi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class GamemodeTest {

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
    void currentDefaultsToEmpty() {
        assertEquals("", Gamemode.current());
    }

    @Test
    void setPersistsCurrentValue() {
        Gamemode.set("catali");
        assertEquals("catali", Gamemode.current());
        assertEquals("catali", Core.settings.getString(Gamemode.KEY, ""));
    }

    @Test
    void activeMatchesCurrentMode() {
        Gamemode.set("catali");
        assertTrue(Gamemode.active("catali"));
        assertFalse(Gamemode.active("pvp"));
    }

    @Test
    void activeIsCaseInsensitive() {
        Gamemode.set("Catali");
        assertTrue(Gamemode.active("catali"));
        assertTrue(Gamemode.active("CATALI"));
    }

    @Test
    void activeMatchesAnyOfMultipleModes() {
        Gamemode.set("towerdefense");
        assertTrue(Gamemode.active("survival", "towerdefense"));
        assertFalse(Gamemode.active("survival", "attack"));
    }

    @Test
    void activeWithEmptyModesIsFalse() {
        Gamemode.set("catali");
        assertFalse(Gamemode.active());
    }
}
