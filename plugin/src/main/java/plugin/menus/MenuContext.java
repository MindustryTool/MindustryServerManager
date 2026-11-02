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
import mindustry.ui.builder.UiBuilder.NodeBuilder;
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

    public MenuContext(Session session, T state, long token) {
        this.session = Objects.requireNonNull(session, "session must not be null");
        this.state = state;
        this.token = token;
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

    public MenuContext<T> add(NodeBuilder<?> node) {
        root.add(node);
        return this;
    }

    public MenuContext<T> row() {
        root.row();
        return this;
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

    @FunctionalInterface
    public interface MenuAction<T> {
        void handle(Session session, T state, MenuResult result);
    }
}
