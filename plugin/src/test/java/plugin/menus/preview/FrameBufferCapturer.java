package plugin.menus.preview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.FrameBuffer;
import arc.scene.ui.layout.Scl;
import arc.util.ScreenUtils;
import mindustry.Vars;
import mindustry.ui.builder.UiTreeBuilder;
import mindustry.ui.dialogs.BaseDialog;
import plugin.menus.MenuContext;
import plugin.menus.ServerMenu;
import plugin.session.Session;

public final class FrameBufferCapturer {

    private static FrameBuffer frameBuffer;

    private FrameBufferCapturer() {
    }

    public static void capture(ServerMenu<?> menu, Session session, Path outputPath, boolean fullScreen) {
        capture(menu, session, outputPath, fullScreen, MenuViewport.DESKTOP);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void capture(ServerMenu<?> menu, Session session, Path outputPath, boolean fullScreen, MenuViewport viewport) {
        Objects.requireNonNull(menu, "menu must not be null");
        Objects.requireNonNull(session, "session must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(viewport, "viewport must not be null");

        MenuPreviewBackend.getInstance().runOnGlThread(() -> {
            boolean originalMobile = Vars.mobile;
            try {
                if (outputPath.getParent() != null) {
                    Files.createDirectories(outputPath.getParent());
                }

                // Pin the UI scale: the harness boots Arc with whatever scale the machine reports,
                // which makes explicit cell sizes inconsistent between captures and with font metrics.
                Scl.setProduct(1f);

                int screenW = viewport.width;
                int screenH = viewport.height;

                Vars.mobile = viewport.mobile;
                if (session.player != null && session.player.con != null) {
                    session.player.con.mobile = viewport.mobile;
                }

                Core.scene.resize(screenW, screenH);

                if (frameBuffer == null || frameBuffer.getWidth() != screenW || frameBuffer.getHeight() != screenH) {
                    if (frameBuffer != null) {
                        frameBuffer.dispose();
                    }
                    frameBuffer = new FrameBuffer(screenW, screenH);
                }

                menu.setSession(session);
                MenuContext ctx = new MenuContext<>(session, menu.getState(), 1L);
                menu.build(ctx);

                BaseDialog dialog = new BaseDialog(menu.getTitle() != null ? menu.getTitle() : "");
                dialog.setFillParent(menu.isFillScreen());
                if (menu.getTitle() != null && !menu.getTitle().isEmpty()) {
                    dialog.title.setText(menu.getTitle());
                } else {
                    dialog.titleTable.remove();
                }

                UiTreeBuilder.build(dialog.cont, ctx.getRoot());
                dialog.pack();
                dialog.show(Core.scene, null);
                dialog.centerWindow();
                dialog.validate();

                Core.scene.act(0f);

                frameBuffer.begin();
                Gl.clearColor(0.08f, 0.08f, 0.1f, 1f);
                Gl.clear(Gl.colorBufferBit | Gl.depthBufferBit);

                Core.scene.draw();
                Draw.flush();

                int captureX;
                int captureY;
                int captureW;
                int captureH;

                if (fullScreen) {
                    captureX = 0;
                    captureY = 0;
                    captureW = screenW;
                    captureH = screenH;
                } else {
                    int pad = 20;
                    captureX = Math.max(0, (int) dialog.x - pad);
                    captureY = Math.max(0, (int) dialog.y - pad);
                    captureW = Math.min((int) dialog.getWidth() + pad * 2, screenW - captureX);
                    captureH = Math.min((int) dialog.getHeight() + pad * 2, screenH - captureY);
                }

                Fi targetFi = Fi.get(outputPath.toAbsolutePath().toString());
                ScreenUtils.saveScreenshot(targetFi, captureX, captureY, captureW, captureH);

                frameBuffer.end();

                dialog.hide(null);
                dialog.remove();
            } catch (Exception e) {
                throw new RuntimeException("Failed capturing menu to " + outputPath, e);
            } finally {
                Vars.mobile = originalMobile;
                Core.scene.resize(1920, 1080);
            }
        });
    }
}
