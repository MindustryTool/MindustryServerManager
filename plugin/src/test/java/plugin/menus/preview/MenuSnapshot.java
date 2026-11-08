package plugin.menus.preview;

import java.nio.file.Path;
import java.util.Objects;
import plugin.menus.ServerMenu;
import plugin.session.Session;

public final class MenuSnapshot {

    private final ServerMenu<?> menu;
    private final Session session;
    private final boolean fullScreen;
    private final MenuViewport viewport;

    private MenuSnapshot(Builder builder) {
        this.menu = Objects.requireNonNull(builder.menu, "menu must not be null");
        this.session = builder.session != null ? builder.session : MockSession.builder().build();
        this.fullScreen = builder.fullScreen;
        this.viewport = builder.viewport;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static void capture(ServerMenu<?> menu, Path outputPath) {
        builder().menu(menu).capture(outputPath);
    }

    public void toFile(Path outputPath) {
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        if (viewport != null) {
            Path targetPath = resolveSuffixedPath(outputPath, viewport);
            FrameBufferCapturer.capture(menu, session, targetPath, fullScreen, viewport);
        } else {
            for (MenuViewport vp : MenuViewport.values()) {
                Path targetPath = resolveSuffixedPath(outputPath, vp);
                FrameBufferCapturer.capture(menu, session, targetPath, fullScreen, vp);
            }
        }
    }

    public static Path resolveSuffixedPath(Path outputPath, MenuViewport viewport) {
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(viewport, "viewport must not be null");

        Path fileNamePath = outputPath.getFileName();
        String fileName = fileNamePath != null ? fileNamePath.toString() : "";
        String suffix = viewport.suffix();

        String newFileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            String baseName = fileName.substring(0, dotIndex);
            String extension = fileName.substring(dotIndex);
            newFileName = baseName + "-" + suffix + extension;
        } else {
            newFileName = fileName + "-" + suffix;
        }

        Path parent = outputPath.getParent();
        return parent != null ? parent.resolve(newFileName) : Path.of(newFileName);
    }

    public static final class Builder {
        private ServerMenu<?> menu;
        private Session session;
        private boolean fullScreen = true;
        private MenuViewport viewport;

        private Builder() {
        }

        public Builder menu(ServerMenu<?> menu) {
            this.menu = Objects.requireNonNull(menu, "menu must not be null");
            return this;
        }

        public Builder session(Session session) {
            this.session = Objects.requireNonNull(session, "session must not be null");
            return this;
        }

        public Builder fullScreen(boolean fullScreen) {
            this.fullScreen = fullScreen;
            return this;
        }

        public Builder viewport(MenuViewport viewport) {
            this.viewport = Objects.requireNonNull(viewport, "viewport must not be null");
            return this;
        }

        public void capture(Path outputPath) {
            new MenuSnapshot(this).toFile(outputPath);
        }
    }
}

