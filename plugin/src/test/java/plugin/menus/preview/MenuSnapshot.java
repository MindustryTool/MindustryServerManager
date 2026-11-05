package plugin.menus.preview;

import java.nio.file.Path;
import java.util.Objects;
import plugin.menus.ServerMenu;
import plugin.session.Session;

public final class MenuSnapshot {

    private final ServerMenu<?> menu;
    private final Session session;
    private final boolean fullScreen;

    private MenuSnapshot(Builder builder) {
        this.menu = Objects.requireNonNull(builder.menu, "menu must not be null");
        this.session = builder.session != null ? builder.session : MockSession.builder().build();
        this.fullScreen = builder.fullScreen;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static void capture(ServerMenu<?> menu, Path outputPath) {
        builder().menu(menu).capture(outputPath);
    }

    public void toFile(Path outputPath) {
        FrameBufferCapturer.capture(menu, session, outputPath, fullScreen);
    }

    public static final class Builder {
        private ServerMenu<?> menu;
        private Session session;
        private boolean fullScreen = false;

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

        public void capture(Path outputPath) {
            new MenuSnapshot(this).toFile(outputPath);
        }
    }
}
