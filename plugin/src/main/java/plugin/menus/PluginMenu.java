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
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.PluginEvents;
import plugin.ServerController;

public abstract class PluginMenu<T> {
    private static final AtomicInteger ID_GEN = new AtomicInteger(1000);
    private static final ConcurrentHashMap<Class<?>, Integer> CLASS_IDS = new ConcurrentHashMap<>();
    private static final Seq<PluginMenu<?>> menus = new Seq<>(new Seq<>());

    public static void init() {
        PluginEvents.on(MenuOptionChooseEvent.class, event -> {
            var playerMenus = getMenus(event.player);

            var targetMenu = playerMenus.find(m -> m.getMenuId() == event.menuId);

            if (targetMenu == null) {
                return;
            }

            ServerController.backgroundTask("Menu Option Choose", () -> {
                synchronized (event.player) {
                    HudOption<Object> selectedOption = null;

                    int i = 0;
                    for (var ops : targetMenu.options) {
                        for (var op : ops) {
                            if (i == event.option) {
                                selectedOption = (HudOption<Object>) op;
                                break;
                            }
                            i++;
                        }
                    }

                    if (selectedOption == null) {
                        Log.err("Failed to find selected option for menu @ with id @", targetMenu, event.option);
                    }

                    if (selectedOption != null && selectedOption.callback != null) {
                        selectedOption.callback.accept(event.player, targetMenu.state);
                    }

                    menus.remove(targetMenu);
                    Call.hideFollowUpMenu(event.player.con, targetMenu.getMenuId());

                    var remainingMenus = getMenus(event.player);

                    if (remainingMenus.size > 0) {
                        var nextMenu = remainingMenus.first();
                        nextMenu.show();
                    }
                }
            });
        });

        PluginEvents.on(PlayerLeave.class, event -> {
            menus.removeAll(m -> m.player == event.player);
        });

        ServerController.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            menus.removeAll(m -> {
                var delete = Instant.now().isAfter(m.createdAt.plusSeconds(60 * 5));

                if (delete && m.player.con != null && m.player.con.isConnected()) {
                    Call.hideFollowUpMenu(m.player.con, m.getMenuId());
                }

                return delete;
            });
        }, 0, 1, TimeUnit.MINUTES);
    }

    public String title = "";
    public String description = "";
    public Player player = null;
    public T state = null;

    public final Instant createdAt = Instant.now();

    private Seq<Seq<HudOption<T>>> options = new Seq<>();

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
        options.get(options.size - 1).add(new HudOption<>(callback, text));
    }

    public void text(String text) {
        options.get(options.size - 1).add(new HudOption<>(null, text));
    }

    public void row() {
        Seq<HudOption<T>> row = new Seq<>();
        options.add(row);
    }

    public abstract void build(Player player, T state);

    private void show() {
        String[][] optionTexts = new String[options.size][];

        for (int i = 0; i < options.size; i++) {
            var op = options.get(i);

            optionTexts[i] = op.map(data -> data.getText()).toArray(String.class);
        }

        Call.menu(player.con, getMenuId(), title, description, optionTexts);
    }

    public synchronized void send(Player player, T state) {
        try {
            PluginMenu<T> copy = getClass().getDeclaredConstructor().newInstance();

            copy.title = title;
            copy.description = description;
            copy.player = player;
            copy.state = state;
            copy.options = new Seq<>();

            ServerController.backgroundTask("Show Menu", () -> {
                copy.build(player, state);

                copy.options.removeAll(op -> op.size == 0);

                var playerMenus = getMenus(player);

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
        return menus.select(m -> m.player == player);
    }

    @Data
    @RequiredArgsConstructor
    public static class HudOption<T> {
        private final PlayerPressCallback<T> callback;
        private final String text;
    }

    @FunctionalInterface
    public interface PlayerPressCallback<T> {
        void accept(Player player, T state);
    }
}
