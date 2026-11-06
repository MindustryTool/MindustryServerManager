package plugin.menus.preview;

public enum MenuViewport {
    DESKTOP(1920, 1080, false),
    MOBILE_LANDSCAPE(720, 360, true),
    MOBILE_PORTRAIT(360, 720, true);

    public final int width;
    public final int height;
    public final boolean mobile;

    MenuViewport(int width, int height, boolean mobile) {
        this.width = width;
        this.height = height;
        this.mobile = mobile;
    }
}
