package plugin.menus;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import arc.func.Cons;
import arc.func.Cons2;
import lombok.Getter;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.ButtonBuilder;
import mindustry.ui.builder.UiBuilder.ButtonTableBuilder;
import mindustry.ui.builder.UiBuilder.CheckBuilder;
import mindustry.ui.builder.UiBuilder.DefaultsBuilder;
import mindustry.ui.builder.UiBuilder.FieldBuilder;
import mindustry.ui.builder.UiBuilder.ImageBuilder;
import mindustry.ui.builder.UiBuilder.ImageButtonBuilder;
import mindustry.ui.builder.UiBuilder.LabelBuilder;
import mindustry.ui.builder.UiBuilder.NodeBuilder;
import mindustry.ui.builder.UiBuilder.PaneBuilder;
import mindustry.ui.builder.UiBuilder.SliderBuilder;
import mindustry.ui.builder.UiBuilder.SpaceBuilder;
import mindustry.ui.builder.UiBuilder.StackBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.session.Session;

@Getter
public class MenuContext<T> {

    private final Session session;
    private final T state;
    private final long token;
    private final TableBuilder root = UiBuilder.table();
    private final Map<String, MenuAction<T>> actions = new HashMap<>();
    private final AtomicInteger actionCounter = new AtomicInteger();

    public static final float DIALOG_HORIZONTAL_MARGIN = 24f;
    public static final float SCROLLBAR_RESERVE = 24f;
    public static final float DESKTOP_MAX_DIALOG_WIDTH = 360f;

    public static final float SAFE_WIDTH = 260f;
    public static final float SAFE_CARD_WIDTH = 240f;
    public static final float MOBILE_MAX_PANE_HEIGHT = 220f;

    public MenuContext(Session session, T state, long token) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.state = state;
        this.token = token;
    }

    public boolean isMobile() {
        return session.player != null && session.player.con != null && session.player.con.mobile;
    }

    public float getViewportWidth() {
        if (session.player != null && session.player.con != null && session.player.con.viewWidth > 0) {
            return session.player.con.viewWidth;
        }
        return isMobile() ? SAFE_WIDTH : 1200f;
    }

    public float getDialogWidth() {
        float available = getViewportWidth() - DIALOG_HORIZONTAL_MARGIN;
        return Math.min(DESKTOP_MAX_DIALOG_WIDTH, Math.max(200f, available));
    }

    public float getCardWidth(float paneWidth) {
        return Math.max(160f, paneWidth - SCROLLBAR_RESERVE);
    }

    public float safeCardWidth() {
        return getCardWidth(getDialogWidth());
    }

    public float safeWidth() {
        return getDialogWidth();
    }

    public int getResponsiveColumns(float itemWidth, float spacing) {
        float availableWidth = getViewportWidth();
        return Math.max(1, (int) Math.floor((availableWidth - 32f + spacing) / (itemWidth + spacing)));
    }

    public float safeMaxPaneHeight(float requestedHeight) {
        if (isMobile()) {
            if (session.player != null && session.player.con != null && session.player.con.viewHeight > 0) {
                float dynamicMax = Math.max(100f, session.player.con.viewHeight - 190f);
                return Math.min(requestedHeight, Math.min(dynamicMax, MOBILE_MAX_PANE_HEIGHT));
            }
            return Math.min(requestedHeight, MOBILE_MAX_PANE_HEIGHT);
        }
        return requestedHeight;
    }

    public String registerAction(MenuAction<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        String actionId = "act_" + actionCounter.incrementAndGet();
        actions.put(actionId, action);
        return actionId;
    }

    public void registerAction(String actionId, MenuAction<T> action) {
        Objects.requireNonNull(actionId, "actionId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        actions.put(actionId, action);
    }

    public TableBuilder table(Cons<TableBuilder> builder) {
        TableBuilder table = UiBuilder.table();
        builder.get(table);
        root.add(table);
        return table;
    }

    public TableBuilder table() {
        return root;
    }

    public PaneBuilder pane(Cons<TableBuilder> contentBuilder) {
        return pane(safeWidth(), 260f, contentBuilder);
    }

    public PaneBuilder pane(float height, Cons<TableBuilder> contentBuilder) {
        return pane(safeWidth(), height, contentBuilder);
    }

    public PaneBuilder pane(float width, float height, Cons<TableBuilder> contentBuilder) {
        TableBuilder content = UiBuilder.table().align("top").align("left");
        contentBuilder.get(content);
        PaneBuilder pane = UiBuilder.pane().add(content);
        pane.width(Math.min(width, safeWidth())).maxHeight(safeMaxPaneHeight(height));
        root.add(pane);
        return pane;
    }

    public LabelBuilder label(String text) {
        return label(text, lbl -> {});
    }

    public LabelBuilder label(String text, Cons<LabelBuilder> configure) {
        LabelBuilder lbl = UiBuilder.label(text)
            .wrap()
            .width(safeWidth())
            .labelAlign("left")
            .align("left")
            .padLeft(4f);
        configure.get(lbl);
        root.add(lbl);
        return lbl;
    }

    public MenuContext<T> add(NodeBuilder<?> node) {
        root.add(node);
        return this;
    }

    public MenuContext<T> row() {
        root.row();
        return this;
    }

    public SpaceBuilder space() {
        SpaceBuilder sp = UiBuilder.space();
        root.add(sp);
        return sp;
    }

    public ButtonBuilder button(String text, MenuAction<T> action) {
        String actionId = registerAction(action);
        ButtonBuilder btn = UiBuilder.button(text).clicked(actionId);
        root.add(btn);
        return btn;
    }

    public ButtonBuilder button(String text, Cons<Session> action) {
        return button(text, (sess, st, res) -> action.get(sess));
    }

    public ButtonBuilder button(String text, Cons2<Session, T> action) {
        return button(text, (sess, st, res) -> action.get(sess, st));
    }

    public ButtonBuilder button(ButtonBuilder btn, MenuAction<T> action) {
        String actionId = registerAction(action);
        btn.clicked(actionId);
        root.add(btn);
        return btn;
    }

    public ButtonBuilder option(String text, Cons2<Session, T> action) {
        return button(text, action);
    }

    public ButtonBuilder option(String text, Cons<Session> action) {
        return button(text, action);
    }

    public ButtonBuilder option(String text, MenuAction<T> action) {
        return button(text, action);
    }

    public FieldBuilder field(String text, String hint, Cons<String> onEnter) {
        String actionId = "fld_" + actionCounter.incrementAndGet();
        registerAction(actionId, (sess, st, res) -> {
            String entered = res.getString(actionId, text);
            onEnter.get(entered);
        });
        FieldBuilder fld = UiBuilder.field(text).hint(hint).enter(actionId);
        fld.id(actionId);
        root.add(fld);
        return fld;
    }

    public CheckBuilder check(String text, boolean checked, Cons<Boolean> onChange) {
        String checkId = "chk_" + actionCounter.incrementAndGet();
        CheckBuilder chk = UiBuilder.check(text).checked(checked);
        chk.id(checkId);
        root.add(chk);
        return chk;
    }

    public SliderBuilder slider(float min, float max, float step, float def) {
        String sliderId = "sld_" + actionCounter.incrementAndGet();
        SliderBuilder sld = UiBuilder.slider(min, max, step).defaultValue(def);
        sld.id(sliderId);
        root.add(sld);
        return sld;
    }

    public ImageBuilder image(String region) {
        return image(region, img -> {});
    }

    public ImageBuilder image(String region, Cons<ImageBuilder> configure) {
        ImageBuilder img = UiBuilder.image(region);
        configure.get(img);
        root.add(img);
        return img;
    }

    public ImageButtonBuilder imageButton(String icon, MenuAction<T> action) {
        String actionId = registerAction(action);
        ImageButtonBuilder btn = UiBuilder.imageButton(icon).clicked(actionId);
        root.add(btn);
        return btn;
    }

    public ImageButtonBuilder imageButton(String icon, Cons<Session> action) {
        return imageButton(icon, (sess, st, res) -> action.get(sess));
    }

    public ImageButtonBuilder imageButton(String icon, Cons2<Session, T> action) {
        return imageButton(icon, (sess, st, res) -> action.get(sess, st));
    }

    public ImageButtonBuilder imageButton(ImageButtonBuilder btn, MenuAction<T> action) {
        String actionId = registerAction(action);
        btn.clicked(actionId);
        root.add(btn);
        return btn;
    }

    public ButtonTableBuilder buttonTable(Cons<ButtonTableBuilder> builder, MenuAction<T> action) {
        String actionId = registerAction(action);
        ButtonTableBuilder btnTable = UiBuilder.buttonTable().clicked(actionId);
        builder.get(btnTable);
        root.add(btnTable);
        return btnTable;
    }

    public ButtonTableBuilder buttonTable(Cons<ButtonTableBuilder> builder, Cons<Session> action) {
        return buttonTable(builder, (sess, st, res) -> action.get(sess));
    }

    public ButtonTableBuilder buttonTable(Cons<ButtonTableBuilder> builder, Cons2<Session, T> action) {
        return buttonTable(builder, (sess, st, res) -> action.get(sess, st));
    }

    public StackBuilder stack(Cons<StackBuilder> builder) {
        StackBuilder stack = UiBuilder.stack();
        builder.get(stack);
        root.add(stack);
        return stack;
    }

    public DefaultsBuilder defaults(Cons<DefaultsBuilder> configure) {
        DefaultsBuilder defaults = UiBuilder.defaults();
        configure.get(defaults);
        root.add(defaults);
        return defaults;
    }

    @FunctionalInterface
    public interface MenuAction<T> {
        void handle(Session session, T state, MenuResult result);
    }
}
