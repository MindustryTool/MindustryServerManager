package plugin.menus;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import arc.struct.Seq;
import arc.util.Log;
import lombok.Getter;
import lombok.NoArgsConstructor;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder;
import mindustry.ui.builder.UiBuilder.TableBuilder;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.menus.MenuContext.MenuAction;
import plugin.session.Session;
import plugin.session.SessionRemovedEvent;
import plugin.utils.Tr;

@Component
@NoArgsConstructor
public class PluginMenuService {

    private final Seq<QueuedMenu> menus = new Seq<>();
    private final ConcurrentHashMap<Player, ActiveMenuEntry> activeMenus = new ConcurrentHashMap<>();
    private final AtomicLong tokenSequence = new AtomicLong();

    @Getter
    private final int menuBuilderId = Menus.registerMenuBuilder(this::handleMenuBuilderResult);

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    public void cleanStaleMenus() {
        activeMenus.values().removeIf(entry -> {
            boolean delete = Instant.now().isAfter(entry.createdAt.plusSeconds(60 * 30));

            if (delete && entry.session.player != null && entry.session.player.con != null) {
                try {
                    Call.hideMenuBuilder(entry.session.player.con, menuBuilderId);
                } catch (Exception e) {
                    Log.err("Failed to hide menu builder", e);
                }
                showNext(entry.session.player);
            }

            return delete;
        });
    }

    @Listener
    public void onSessionRemoved(SessionRemovedEvent event) {
        if (event == null || event.session == null || event.session.player == null) {
            return;
        }
        menus.removeAll(m -> m.getSession().player == event.session.player);
        activeMenus.remove(event.session.player);
    }

    public void handleMenuBuilderResult(Player player, MenuResult result) {
        if (player == null || result == null) {
            return;
        }

        synchronized (player) {
            ActiveMenuEntry active = activeMenus.get(player);
            if (active == null) {
                showNext(player);
                return;
            }

            if (result.token != active.token) {
                return;
            }

            activeMenus.remove(player);

            if (!result.wasCancelled() && result.result != null) {
                MenuAction<Object> action = active.actions.get(result.result);
                if (action != null) {
                    try {
                        action.handle(active.session, active.state, result);
                    } catch (Exception e) {
                        Log.err("Error handling menu action @ for player @", result.result, player);
                        Log.err(e);
                    }
                }
            }

            showNext(player);
        }
    }

    public void showNext(Player player) {
        if (player == null) {
            return;
        }

        synchronized (player) {
            try {
                menus.removeAll(m -> !m.valid());
                var remaining = menus.select(m -> m.getSession().player == player);
                boolean hasActive = activeMenus.containsKey(player);

                if (!hasActive && !remaining.isEmpty()) {
                    QueuedMenu nextMenu = remaining.first();
                    menus.remove(nextMenu);

                    long token = tokenSequence.incrementAndGet();

                    Tasks.io("Show Menu: " + token, () -> {
                        try {
                            nextMenu.show(this, token);
                        } catch (Exception e) {
                            Log.err("Failed to show menu for player @", player);
                            Log.err(e);
                            activeMenus.remove(player);
                            player.sendMessage(Tr.t(player, "menu.error", "message", e.getMessage()));
                        }
                    });
                }
            } catch (Exception e) {
                Log.err("Failed to advance menu queue for player @", player);
                Log.err(e);
                player.sendMessage(Tr.t(player, "menu.error", "message", e.getMessage()));
            }
        }
    }

    public void add(ServerMenu<?> menu) {
        if (menu == null || menu.getSession() == null) {
            return;
        }
        if (menus.contains(
                m -> m.getMenuClass().equals(menu.getClass()) && m.getSession().player == menu.getSession().player)) {
            return;
        }
        menus.insert(0, new ServerMenuHolder<>(menu));
    }

    public void add(PluginMenu<?> menu) {
        if (menu == null || menu.session == null) {
            return;
        }
        if (menus.contains(
                m -> m.getMenuClass().equals(menu.getClass()) && m.getSession().player == menu.session.player)) {
            return;
        }
        menus.insert(0, new LegacyMenuHolder<>(menu));
    }

    public Seq<PluginMenu<?>> getPlayerMenus(Player player) {
        Seq<PluginMenu<?>> result = new Seq<>();
        for (QueuedMenu q : menus) {
            if (q.getSession().player == player && q instanceof LegacyMenuHolder<?> legacy) {
                result.add(legacy.menu);
            }
        }
        return result;
    }

    public void hide(Player player) {
        if (player == null || player.con == null) {
            return;
        }
        synchronized (player) {
            activeMenus.remove(player);
            try {
                Call.hideMenuBuilder(player.con, menuBuilderId);
            } catch (Exception e) {
                Log.err("Failed to hide menu builder", e);
            }
            showNext(player);
        }
    }

    public void update(Player player, ServerMenu<?> menu, String elementId, TableBuilder table) {
        if (player == null || player.con == null) {
            return;
        }
        Call.menuBuilderUpdate(player.con, menuBuilderId, elementId, table);
    }

    @Destroy
    public void destroy() {
        menus.clear();
        activeMenus.clear();
    }

    public interface QueuedMenu {
        Session getSession();

        boolean valid();

        Class<?> getMenuClass();

        void show(PluginMenuService service, long token);
    }

    private static class ActiveMenuEntry {
        final Session session;
        final Object state;
        final long token;
        final Instant createdAt = Instant.now();
        final Map<String, MenuAction<Object>> actions = new ConcurrentHashMap<>();

        ActiveMenuEntry(Session session, Object state, long token) {
            this.session = session;
            this.state = state;
            this.token = token;
        }
    }

    private static class ServerMenuHolder<T> implements QueuedMenu {
        private final ServerMenu<T> menu;

        ServerMenuHolder(ServerMenu<T> menu) {
            this.menu = menu;
        }

        @Override
        public Session getSession() {
            return menu.getSession();
        }

        @Override
        public boolean valid() {
            return menu.valid();
        }

        @Override
        public Class<?> getMenuClass() {
            return menu.getClass();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void show(PluginMenuService service, long token) {
            menu.setToken(token);
            MenuContext<T> ctx = new MenuContext<>(menu.getSession(), menu.getState(), token);
            try {
                menu.build(ctx);
            } catch (Exception e) {
                menu.getSession().player.sendMessage(Tr.t(menu.getSession(), "menu.error", "message", e.getMessage()));
                Log.err("Failed to build server menu @", e);
                service.activeMenus.remove(menu.getSession().player);
                service.showNext(menu.getSession().player);
                return;
            }

            ActiveMenuEntry entry = new ActiveMenuEntry(menu.getSession(), menu.getState(), token);
            for (var act : ctx.getActions().entrySet()) {
                entry.actions.put(act.getKey(), (sess, st, res) -> act.getValue().handle(sess, (T) st, res));
            }

            service.activeMenus.put(menu.getSession().player, entry);

            Call.menuBuilder(
                    menu.getSession().player.con,
                    service.getMenuBuilderId(),
                    token,
                    menu.getTitle(),
                    menu.isHideOnClick(),
                    menu.isHideExisting(),
                    menu.isFillScreen(),
                    ctx.getRoot());
        }
    }

    private static class LegacyMenuHolder<T> implements QueuedMenu {
        private final PluginMenu<T> menu;

        LegacyMenuHolder(PluginMenu<T> menu) {
            this.menu = menu;
        }

        @Override
        public Session getSession() {
            return menu.session;
        }

        @Override
        public boolean valid() {
            return menu.valid();
        }

        @Override
        public Class<?> getMenuClass() {
            return menu.getClass();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void show(PluginMenuService service, long token) {
            try {
                menu.build();
            } catch (Exception e) {
                menu.session.player.sendMessage(Tr.t(menu.session, "menu.error", "message", e.getMessage()));
                Log.err("Failed to build legacy menu @", e);
                service.activeMenus.remove(menu.session.player);
                service.showNext(menu.session.player);
                return;
            }

            ActiveMenuEntry entry = new ActiveMenuEntry(menu.session, menu.state, token);
            TableBuilder root = UiBuilder.table();

            if (menu.description != null && !menu.description.isEmpty()) {
                root.add(UiBuilder.label(menu.description).wrap().pad(8f)).row();
            }

            menu.options.removeAll(op -> op.size == 0);
            int optIndex = 0;

            for (int i = 0; i < menu.options.size; i++) {
                var rowOptions = menu.options.get(i);
                TableBuilder rowTable = UiBuilder.table();

                for (var op : rowOptions) {
                    String actionId = "opt_" + (optIndex++);
                    if (op.getCallback() != null) {
                        entry.actions.put(actionId, (sess, st, res) -> op.getCallback().accept(sess, (T) st));
                        rowTable.add(UiBuilder.button(op.getText()).clicked(actionId).pad(4f).width(160f).height(45f));
                    } else {
                        rowTable.add(UiBuilder.button(op.getText()).disabled(true).pad(4f).width(160f).height(45f));
                    }
                }
                root.add(rowTable).row();
            }

            service.activeMenus.put(menu.session.player, entry);

            Call.menuBuilder(
                    menu.session.player.con,
                    service.getMenuBuilderId(),
                    token,
                    menu.title,
                    true,
                    true,
                    false,
                    root);
        }
    }
}
