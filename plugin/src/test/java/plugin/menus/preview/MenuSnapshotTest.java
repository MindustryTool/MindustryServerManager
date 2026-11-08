package plugin.menus.preview;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuSnapshotTest {

    @Test
    void resolveSuffixedPathAppendsSuffixCorrectly() {
        Path standard = Path.of("output", "menu.png");
        assertEquals(Path.of("output", "menu-desktop.png"), MenuSnapshot.resolveSuffixedPath(standard, MenuViewport.DESKTOP));
        assertEquals(Path.of("output", "menu-mobile-landscape.png"), MenuSnapshot.resolveSuffixedPath(standard, MenuViewport.MOBILE_LANDSCAPE));
        assertEquals(Path.of("output", "menu-mobile-portrait.png"), MenuSnapshot.resolveSuffixedPath(standard, MenuViewport.MOBILE_PORTRAIT));

        Path noExt = Path.of("output", "menu");
        assertEquals(Path.of("output", "menu-desktop"), MenuSnapshot.resolveSuffixedPath(noExt, MenuViewport.DESKTOP));

        Path multiDot = Path.of("dir", "sub", "menu.v1.png");
        assertEquals(Path.of("dir", "sub", "menu.v1-desktop.png"), MenuSnapshot.resolveSuffixedPath(multiDot, MenuViewport.DESKTOP));

        Path hidden = Path.of(".snapshot");
        assertEquals(Path.of(".snapshot-desktop"), MenuSnapshot.resolveSuffixedPath(hidden, MenuViewport.DESKTOP));
    }

    @Test
    void viewportSuffixMatchesKebabCase() {
        assertEquals("desktop", MenuViewport.DESKTOP.suffix());
        assertEquals("mobile-landscape", MenuViewport.MOBILE_LANDSCAPE.suffix());
        assertEquals("mobile-portrait", MenuViewport.MOBILE_PORTRAIT.suffix());
    }

    @Test
    void captureAllViewportsByDefault(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("preview.png");

        ServerMenu<Void> menu = new ServerMenu<>() {
            @Override
            public void build(MenuContext<Void> ctx) {
                ctx.label("Default Viewports Test");
            }
        };
        menu.setTitle("Preview Test");

        MenuSnapshot.capture(menu, target);

        Path desktopPath = tempDir.resolve("preview-desktop.png");
        Path mobileLandPath = tempDir.resolve("preview-mobile-landscape.png");
        Path mobilePortPath = tempDir.resolve("preview-mobile-portrait.png");

        assertTrue(Files.exists(desktopPath), "Desktop preview should exist");
        assertTrue(Files.exists(mobileLandPath), "Mobile landscape preview should exist");
        assertTrue(Files.exists(mobilePortPath), "Mobile portrait preview should exist");

        BufferedImage desktopImg = ImageIO.read(desktopPath.toFile());
        assertNotNull(desktopImg);
        assertEquals(1920, desktopImg.getWidth());
        assertEquals(1080, desktopImg.getHeight());

        BufferedImage landImg = ImageIO.read(mobileLandPath.toFile());
        assertNotNull(landImg);
        assertEquals(720, landImg.getWidth());
        assertEquals(360, landImg.getHeight());

        BufferedImage portImg = ImageIO.read(mobilePortPath.toFile());
        assertNotNull(portImg);
        assertEquals(360, portImg.getWidth());
        assertEquals(720, portImg.getHeight());
    }

    @Test
    void captureExplicitSingleViewportWithSuffix(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("single.png");

        ServerMenu<Void> menu = new ServerMenu<>() {
            @Override
            public void build(MenuContext<Void> ctx) {
                ctx.label("Single Viewport Test");
            }
        };

        MenuSnapshot.builder()
                .menu(menu)
                .viewport(MenuViewport.MOBILE_LANDSCAPE)
                .capture(target);

        Path expectedPath = tempDir.resolve("single-mobile-landscape.png");
        Path unexpectedDesktop = tempDir.resolve("single-desktop.png");

        assertTrue(Files.exists(expectedPath), "Target single mobile landscape preview should exist");
        assertFalse(Files.exists(unexpectedDesktop), "Desktop variant should not be rendered");

        BufferedImage img = ImageIO.read(expectedPath.toFile());
        assertNotNull(img);
        assertEquals(720, img.getWidth());
        assertEquals(360, img.getHeight());
    }

    @Test
    void captureCroppedDialogWhenFullScreenFalse(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("cropped.png");

        ServerMenu<Void> menu = new ServerMenu<>() {
            @Override
            public void build(MenuContext<Void> ctx) {
                ctx.label("Cropped Dialog Test");
            }
        };

        MenuSnapshot.builder()
                .menu(menu)
                .fullScreen(false)
                .viewport(MenuViewport.DESKTOP)
                .capture(target);

        Path expectedPath = tempDir.resolve("cropped-desktop.png");
        assertTrue(Files.exists(expectedPath));

        BufferedImage img = ImageIO.read(expectedPath.toFile());
        assertNotNull(img);
        // Cropped dialog dimensions should be smaller than full 1920x1080 screen
        assertTrue(img.getWidth() < 1920, "Cropped width should be smaller than screen width");
        assertTrue(img.getHeight() < 1080, "Cropped height should be smaller than screen height");
    }
}
