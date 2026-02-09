package plugin.menus;

import java.time.Instant;

import arc.struct.Seq;
import arc.util.Log;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Call;
import plugin.Tasks;
import plugin.core.Registry;
import plugin.service.PluginMenuService;
import plugin.type.Session;

public abstract class PluginMenu<T> {

    public String title = "";
    public String description = "";
    public Session session = null;
    public T state = null;

    @Getter
    private boolean isSent = false;
    private boolean sendable = false;

    public final Instant createdAt = Instant.now();

    private Seq<Seq<HudOption<T>>> options = new Seq<>(new Seq<>());

    public final int getMenuId() {
        return Registry.get(PluginMenuService.class).getMenuId(getClass());
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

    public void show() {
        if (isSent) {
            Log.err("Menu @ has been sent at @", this, createdAt);
            Thread.dumpStack();
        }

        if (!sendable) {
            Log.err("Cant send @", this);
            Thread.dumpStack();
        }

        isSent = true;

        Tasks.io("Show Menu: " + getMenuId(), () -> {
            try {
                this.build(session, state);
            } catch (Exception e) {
                session.player.sendMessage("[scarlet]Error: [white]" + e.getMessage());
                Log.err("Failed to build menu @ for player @ with state @", this, session, state);
                Log.err(e);
                return;
            }

            options.removeAll(op -> op.size == 0);

            String[][] optionTexts = new String[options.size][];

            for (int i = 0; i < options.size; i++) {
                var op = options.get(i);

                optionTexts[i] = op.map(data -> data.getText()).toArray(String.class);
            }

            Call.menu(session.player.con, getMenuId(), title, description, optionTexts);
        });
    }

    public void send(Session session, T state) {
        try {
            @SuppressWarnings("unchecked")
            PluginMenu<T> copy = getClass().getDeclaredConstructor().newInstance();

            copy.title = title;
            copy.description = description;
            copy.session = session;
            copy.state = state;
            copy.options = new Seq<>(new Seq<>());
            copy.sendable = true;

            var handler = Registry.get(PluginMenuService.class);
            var playerMenus = handler.getValidMenus(session.player);
            var exists = playerMenus.find(m -> m == copy);

            if (exists != null) {
                Log.err("Menu @ already in the list", copy);
            }

            if (playerMenus.contains(m -> m.getMenuId() == copy.getMenuId())) {
                Log.warn("Player @ already have menu @", session.player, copy);
                return;
            }

            handler.add(copy);

            if (!playerMenus.contains(m -> m.isSent)) {
                copy.show();
            }
        } catch (Exception e) {
            session.player.sendMessage("[scarlet]Error: [white]" + e.getMessage());
            Log.err(e);
        }
    }

    @SuppressWarnings("unchecked")
    public Seq<HudOption<Object>> getFlattenedOptions() {
        return (Seq<HudOption<Object>>) (Object) options.flatten();
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

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "(" + getMenuId() + ")[" + sendable + ", " + isSent + "]";
    }
}
