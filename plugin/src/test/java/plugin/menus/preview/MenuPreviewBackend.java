package plugin.menus.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import arc.Core;
import arc.Settings;
import arc.backend.sdl.SdlApplication;
import arc.backend.sdl.SdlConfig;
import arc.files.Fi;
import arc.util.Log;
import mindustry.ClientLauncher;
import mindustry.Vars;
import mindustry.content.TechTree;
import mindustry.core.ContentLoader;
import mindustry.core.Version;
import mindustry.desktop.DesktopLauncher;

public final class MenuPreviewBackend {

    private static final Object LOCK = new Object();
    private static volatile MenuPreviewBackend instance;

    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

    private MenuPreviewBackend() {
        System.setProperty("nodiscord", "true");
        Thread thread = new Thread(this::launch, "MenuPreview-Arc-Thread");
        thread.setDaemon(true);
        thread.start();
    }

    public static MenuPreviewBackend getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new MenuPreviewBackend();
                }
            }
        }
        return instance;
    }

    private void launch() {
        try {
            Path tempDir = Files.createTempDirectory("mindustry-preview-data");
            tempDir.toFile().deleteOnExit();
            Fi tempFi = new Fi(tempDir.toFile());
            Core.settings = new Settings();
            Core.settings.setDataDirectory(tempFi);

            Vars.dataDirectory = tempFi;
            Vars.modDirectory = tempFi.child("mods");
            Vars.skipModCode = true;

            if (TechTree.all != null) {
                TechTree.all.clear();
            }
            if (TechTree.roots != null) {
                TechTree.roots.clear();
            }
            Vars.content = new ContentLoader();
            Version.init();
            Vars.loadLogger();

            SdlConfig config = new SdlConfig();
            config.title = "Mindustry Dev Preview";
            config.width = 1920;
            config.height = 1080;
            config.initialVisible = false;
            config.disableAudio = true;

            DesktopLauncher launcher = new DesktopLauncher(new String[0]);
            ClientLauncher.runOnClientLoad(() -> readyFuture.complete(null));

            new SdlApplication(launcher, config);
        } catch (Throwable t) {
            Log.err("Failed to boot Arc preview backend", t);
            readyFuture.completeExceptionally(t);
        }
    }

    public void awaitReady() {
        try {
            readyFuture.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Timed out waiting for Mindustry client assets to load", e);
        }
    }

    public <T> T runOnGlThread(Supplier<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        awaitReady();

        CompletableFuture<T> future = new CompletableFuture<>();
        Core.app.post(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed executing task on Arc GL thread", e);
        }
    }

    public void runOnGlThread(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        runOnGlThread(() -> {
            task.run();
            return null;
        });
    }
}
