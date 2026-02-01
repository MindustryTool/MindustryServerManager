package plugin.menus;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import arc.struct.Seq;
import arc.util.Log;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.MenuOptionChooseEvent;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.event.SessionRemovedEvent;
import plugin.handler.SessionHandler;
import plugin.type.Session;

public abstract class PluginMenu<T> {
    private static final AtomicInteger ID_GEN = new AtomicInteger(1000);
    private static final ConcurrentHashMap<Class<?>, Integer> CLASS_IDS = new ConcurrentHashMap<>();
    private static final Seq<PluginMenu<?>> menus = new Seq<>();

    public static void init() {
        PluginEvents.on(MenuOptionChooseEvent.class, event -> {
            var targetMenu = menus.find(m -> m.getMenuId() == event.menuId && m.session.player == event.player);

            if (targetMenu == null) {
                return;
            }

            if (event.option < 0) {
                return;
            }

            ServerControl.backgroundTask("Menu Option Choose", () -> {
                HudOption<Object> selectedOption = null;

                int i = 0;

                if (event.option >= 0) {
                    Seq<HudOption<Object>> flatten = targetMenu.options.flatten();
                    for (var op : flatten) {
                        if (i == event.option) {
                            selectedOption = op;
                            break;
                        }
                        i++;
                    }

                    if (selectedOption == null) {
                        Log.err("Failed to find selected option for menu @ with id @", targetMenu, event.option);
                    }
                }

                synchronized (event.player) {
                    if (selectedOption != null && selectedOption.callback != null) {
                        var session = SessionHandler.get(event.player);
                        selectedOption.callback.accept(session, targetMenu.state);
                        Call.hideFollowUpMenu(event.player.con, targetMenu.getMenuId());
                    }

                    menus.remove(targetMenu);

                    var remainingMenus = getMenus(event.player);

                    if (remainingMenus.size > 0) {
                        var nextMenu = remainingMenus.first();
                        nextMenu.show();
                    }
                }
            });
        });

        PluginEvents.on(SessionRemovedEvent.class, event -> {
            menus.removeAll(m -> m.session.player == event.session.player);
        });

        ServerControl.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            menus.removeAll(m -> {
                var delete = Instant.now().isAfter(m.createdAt.plusSeconds(60 * 5));

                if (delete && m.session.player.con != null && m.session.player.con.isConnected()) {
                    Call.hideFollowUpMenu(m.session.player.con, m.getMenuId());
                }

                return delete;
            });
        }, 0, 1, TimeUnit.MINUTES);
    }

    public static void unload() {
        menus.clear();
    }

    public String title = "";
    public String description = "";
    public Session session = null;
    public T state = null;

    public final Instant createdAt = Instant.now();

    private Seq<Seq<HudOption<T>>> options = new Seq<>(new Seq<>());

    public PluginMenu() {
    }

    public final int getMenuId() {
        return CLASS_IDS.computeIfAbsent(getClass(), cls -> {
            var id = ID_GEN.getAndIncrement();
            Log.info("Register menu @ with id @", cls, id);
            return id;
        });
    }

    public void option(String text, PlayerPressCallback<T> callback) {
        if (options.size == 0) {
            row();
        }
        options.get(options.size - 1).add(new HudOption<>(callback, text));
    }

    public void text(String text) {
        if (options.size == 0) {
            row();
        }
        options.get(options.size - 1).add(new HudOption<>(null, text));
    }

    public void row() {
        Seq<HudOption<T>> row = new Seq<>();
        options.add(row);
    }

    public abstract void build(Session session, T state);

    private void show() {
        String[][] optionTexts = new String[options.size][];

        for (int i = 0; i < options.size; i++) {
            var op = options.get(i);

            optionTexts[i] = op.map(data -> data.getText()).toArray(String.class);
        }

        Call.menu(session.player.con, getMenuId(), title, description, optionTexts);
    }

    public synchronized void send(Session session, T state) {
        try {
            @SuppressWarnings("unchecked")
            PluginMenu<T> copy = getClass().getDeclaredConstructor().newInstance();

            copy.title = title;
            copy.description = description;
            copy.session = session;
            copy.state = state;
            copy.options = new Seq<>(new Seq<>());

            ServerControl.backgroundTask("Show Menu: " + getMenuId(), () -> {
                try {
                    copy.build(session, state);
                } catch (Exception e) {
                    Log.err("Failed to build menu @ for player @ with state @", copy, session, state);
                    return;
                }

                copy.options.removeAll(op -> op.size == 0);

                var playerMenus = getMenus(session.player);

                menus.add(copy);

                if (playerMenus.size == 0) {
                    copy.show();
                }
            });
        } catch (Exception e) {
            Log.info(e);
        }
    }

    public static Seq<PluginMenu<?>> getMenus(Player player) {
        return menus.select(m -> m.session.player == player);
    }

    @Data
    @RequiredArgsConstructor
    public static class HudOption<T> {
        private final PlayerPressCallback<T> callback;
        private final String text;
    }

    @FunctionalInterface
    public interface PlayerPressCallback<T> {
        void accept(Session session, T state);
    }
}
