package plugin.menus.preview;

import java.util.Locale;

public enum MenuViewport {
    DESKTOP(1920, 1080, false),
    MOBILE_LANDSCAPE(720, 360, true),
    MOBILE_PORTRAIT(288, 512, true);

    public final int width;
    public final int height;
    public final boolean mobile;

    MenuViewport(int width, int height, boolean mobile) {
        this.width = width;
        this.height = height;
        this.mobile = mobile;
    }

    public String suffix() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
