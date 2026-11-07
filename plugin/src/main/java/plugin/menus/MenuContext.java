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
import mindustry.ui.builder.UiBuilder.CheckBuilder;
import mindustry.ui.builder.UiBuilder.FieldBuilder;
import mindustry.ui.builder.UiBuilder.LabelBuilder;
import mindustry.ui.builder.UiBuilder.NodeBuilder;
import mindustry.ui.builder.UiBuilder.PaneBuilder;
import mindustry.ui.builder.UiBuilder.SliderBuilder;
import mindustry.ui.builder.UiBuilder.SpaceBuilder;
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

    public static final float SAFE_WIDTH = 320f;
    public static final float SAFE_CARD_WIDTH = 290f;
    public static final float MOBILE_MAX_PANE_HEIGHT = 220f;

    public MenuContext(Session session, T state, long token) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.state = state;
        this.token = token;
    }

    public boolean isMobile() {
        return session.player != null && session.player.con != null && session.player.con.mobile;
    }

    public float safeMaxPaneHeight(float requestedHeight) {
        if (isMobile()) {
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
        return pane(SAFE_WIDTH, 260f, contentBuilder);
    }

    public PaneBuilder pane(float height, Cons<TableBuilder> contentBuilder) {
        return pane(SAFE_WIDTH, height, contentBuilder);
    }

    public PaneBuilder pane(float width, float height, Cons<TableBuilder> contentBuilder) {
        TableBuilder content = UiBuilder.table();
        contentBuilder.get(content);
        PaneBuilder pane = UiBuilder.pane().add(content);
        pane.width(Math.min(width, SAFE_WIDTH)).maxHeight(safeMaxPaneHeight(height));
        root.add(pane);
        return pane;
    }

    public LabelBuilder label(String text) {
        return label(text, lbl -> {});
    }

    public LabelBuilder label(String text, Cons<LabelBuilder> configure) {
        LabelBuilder lbl = UiBuilder.label(text).wrap().width(SAFE_WIDTH);
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

    @FunctionalInterface
    public interface MenuAction<T> {
        void handle(Session session, T state, MenuResult result);
    }
}
