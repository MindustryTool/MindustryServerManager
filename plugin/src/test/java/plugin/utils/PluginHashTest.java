package plugin.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import arc.files.Fi;
import mindustry.Vars;

class PluginHashTest {

    @TempDir
    Path dir;

    @Test
    void hashComputedOnceAndCached() {
        Vars.modDirectory = new Fi(dir.toFile());
        Fi jar = Vars.modDirectory.child("plugin.jar");
        jar.writeBytes(new byte[] { 1, 2, 3 });

        String first = Utils.currentPluginHash();
        assertNotNull(first, "hash of the loaded jar must be computed");

        jar.writeBytes(new byte[] { 9, 9, 9, 9 });

        String second = Utils.currentPluginHash();
        assertEquals(first, second, "hash must stay the start-time value, not the replaced jar");
    }
}
