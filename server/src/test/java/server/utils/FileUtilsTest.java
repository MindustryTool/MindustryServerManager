package server.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import arc.files.Fi;

import static org.junit.jupiter.api.Assertions.*;

public class FileUtilsTest {

    @TempDir
    Path tempDir;

    @Test
    public void testValidRelativePath() throws IOException {
        Path base = tempDir.resolve("server_config");
        Files.createDirectories(base);
        Path targetFile = base.resolve("server.json");
        Files.writeString(targetFile, "{}");

        Fi result = FileUtils.getFile(base.toString(), "server.json");

        assertNotNull(result);
        assertEquals(targetFile.toAbsolutePath().normalize().toString(), Path.of(result.absolutePath()).normalize().toString());
        assertTrue(result.exists());
    }

    @Test
    public void testValidNestedRelativePath() throws IOException {
        Path base = tempDir.resolve("server_config");
        Files.createDirectories(base.resolve("mods"));

        Fi result = FileUtils.getFile(base.toString(), "mods/test-mod.jar");

        assertNotNull(result);
        assertEquals(base.resolve("mods/test-mod.jar").toAbsolutePath().normalize().toString(),
                Path.of(result.absolutePath()).normalize().toString());
    }

    @Test
    public void testLeadingSlashesTreatedAsRelative() throws IOException {
        Path base = tempDir.resolve("server_config");
        Files.createDirectories(base);

        Fi resultForward = FileUtils.getFile(base.toString(), "/maps/map1.msav");
        Fi resultBackward = FileUtils.getFile(base.toString(), "\\maps\\map1.msav");

        assertEquals(base.resolve("maps/map1.msav").toAbsolutePath().normalize().toString(),
                Path.of(resultForward.absolutePath()).normalize().toString());
        assertEquals(base.resolve("maps/map1.msav").toAbsolutePath().normalize().toString(),
                Path.of(resultBackward.absolutePath()).normalize().toString());
    }

    @Test
    public void testNullPathThrowsBadRequest() throws IOException {
        Path base = tempDir.resolve("server_config");
        Files.createDirectories(base);

        ApiError error = assertThrows(ApiError.class, () -> FileUtils.getFile(base.toString(), null));
        assertEquals(400, error.status);
    }

    @Test
    public void testDotDotTraversalThrowsBadRequest() throws IOException {
        Path base = tempDir.resolve("server_config");
        Files.createDirectories(base);

        ApiError error = assertThrows(ApiError.class, () -> FileUtils.getFile(base.toString(), "../outside.txt"));
        assertEquals(400, error.status);

        ApiError winError = assertThrows(ApiError.class, () -> FileUtils.getFile(base.toString(), "..\\outside.txt"));
        assertEquals(400, winError.status);
    }

    @Test
    public void testCurrentDirDotSlashThrowsBadRequest() throws IOException {
        Path base = tempDir.resolve("server_config");
        Files.createDirectories(base);

        ApiError error1 = assertThrows(ApiError.class, () -> FileUtils.getFile(base.toString(), "./nested/file.txt"));
        assertEquals(400, error1.status);

        ApiError error2 = assertThrows(ApiError.class, () -> FileUtils.getFile(base.toString(), ".\\nested\\file.txt"));
        assertEquals(400, error2.status);
    }

    @Test
    public void testFiOverloadDelegatesProperly() throws IOException {
        Path base = tempDir.resolve("server_config");
        Files.createDirectories(base);

        Fi baseFi = new Fi(base.toFile());
        Fi result = FileUtils.getFile(baseFi, "config.json");

        assertNotNull(result);
        assertEquals(base.resolve("config.json").toAbsolutePath().normalize().toString(),
                Path.of(result.absolutePath()).normalize().toString());
    }

    @Test
    public void testSymlinkPointingOutsideThrowsForbidden() throws IOException {
        Path base = tempDir.resolve("server_config");
        Path outside = tempDir.resolve("outside_dir");
        Files.createDirectories(base);
        Files.createDirectories(outside);
        Path secretFile = outside.resolve("secret.txt");
        Files.writeString(secretFile, "sensitive-data");

        Path symlink = base.resolve("symlink_dir");
        try {
            Files.createSymbolicLink(symlink, outside);
        } catch (UnsupportedOperationException | SecurityException | IOException e) {
            // Some Windows environments without SeCreateSymbolicLinkPrivilege may not allow symlink creation
            return;
        }

        ApiError error = assertThrows(ApiError.class, () -> FileUtils.getFile(base.toString(), "symlink_dir/secret.txt"));
        assertEquals(403, error.status);
    }
}
