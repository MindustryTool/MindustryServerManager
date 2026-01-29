package plugin.menus;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
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
            var menus = getMenus(event.player);

            var targetMenu = menus.find(m -> m.getMenuId() == event.menuId);

            if (targetMenu == null) {
                return;
            }

            ServerController.backgroundTask(() -> {
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

                selectedOption.callback.accept(event.player, targetMenu.state);
                menus.remove(targetMenu);
                Call.hideFollowUpMenu(event.player.con, targetMenu.getMenuId());
            });
        });

        PluginEvents.on(PlayerLeave.class, event -> {
            menus.removeAll(m -> m.player == event.player);
        });
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
        return CLASS_IDS.computeIfAbsent(getClass(), cls -> ID_GEN.getAndIncrement());
    }

    public void option(String text, PlayerPressCallback<T> callback) {
        options.get(options.size - 1).add(new HudOption<>(callback, text));
    }

    public void row() {
        Seq<HudOption<T>> row = new Seq<>();
        options.add(row);
    }

    public abstract void build(Player player, T state);

    public synchronized void send(Player player, T state) {
        try {
            PluginMenu<T> copy = getClass().getDeclaredConstructor().newInstance();

            copy.title = title;
            copy.description = description;
            copy.player = player;
            copy.state = state;
            copy.options = new Seq<>();

            ServerController.backgroundTask(() -> {
                copy.build(player, state);

                options.removeAll(op -> op.size == 0);

                String[][] optionTexts = new String[options.size][];

                for (int i = 0; i < options.size; i++) {
                    var op = options.get(i);

                    optionTexts[i] = op.map(data -> data.getText()).toArray(String.class);
                }

                menus.add(copy);

                var playerMenus = getMenus(player);

                if (playerMenus.size == 0) {
                    Call.menu(player.con, getMenuId(), title, description, optionTexts);
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
