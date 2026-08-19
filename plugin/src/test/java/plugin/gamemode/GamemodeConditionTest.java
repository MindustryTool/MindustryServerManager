package plugin.gamemode;

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

public class GamemodeConditionTest {

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
    void checkMatchesCurrentGamemode() {
        Gamemode.set("catali");
        assertTrue(new GamemodeCondition(new String[]{"catali"}).check());
        assertFalse(new GamemodeCondition(new String[]{"pvp"}).check());
    }

    @Test
    void checkIsCaseInsensitive() {
        Gamemode.set("catali");
        assertTrue(new GamemodeCondition(new String[]{"CATALI"}).check());
    }

    @Test
    void checkMatchesAnyMode() {
        Gamemode.set("attack");
        assertTrue(new GamemodeCondition(new String[]{"survival", "attack"}).check());
    }

    @Test
    void emptyArgsFails() {
        Gamemode.set("catali");
        assertFalse(new GamemodeCondition(new String[0]).check());
        assertFalse(new GamemodeCondition(new String[]{}).check());
    }

    @Test
    void noGamemodeSetFails() {
        assertFalse(new GamemodeCondition(new String[]{"catali"}).check());
    }
}
