package plugin.menus;

import java.time.Instant;
import java.util.Objects;
import arc.util.Log;
import lombok.Getter;
import lombok.Setter;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.core.Registry;
import plugin.session.Session;
import plugin.utils.Tr;

@Getter
@Setter
public abstract class ServerMenu<T> {

    protected String title = "";
    protected boolean fillScreen = true;
    protected boolean hideOnClick = true;
    protected boolean hideExisting = true;

    protected Session session;
    protected T state;
    protected long token;
    protected final Instant createdAt = Instant.now();

    public boolean valid() {
        return session != null && session.player != null && session.player.con != null;
    }

    public abstract void build(MenuContext<T> ctx);

    public void send(Session session, T state) {
        Objects.requireNonNull(session, "session must not be null");
        try {
            @SuppressWarnings("unchecked")
            ServerMenu<T> copy = Registry.inject(this.getClass());
            copy.title = title;
            copy.fillScreen = fillScreen;
            copy.hideOnClick = hideOnClick;
            copy.hideExisting = hideExisting;
            copy.session = session;
            copy.state = state;

            var service = Registry.get(PluginMenuService.class);
            service.add(copy);
            service.showNext(session.player);
        } catch (Exception e) {
            if (session.player != null) {
                session.player.sendMessage(Tr.t(session, "menu.error", "message", e.getMessage()));
            }
            Log.err("Failed to send server menu", e);
        }
    }

    public void update(String elementId, TableBuilder table) {
        if (session == null || session.player == null) {
            return;
        }
        var service = Registry.get(PluginMenuService.class);
        service.update(session.player, this, elementId, table);
    }
}
