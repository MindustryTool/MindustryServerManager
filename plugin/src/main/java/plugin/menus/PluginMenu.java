package plugin.menus;

import java.time.Instant;

import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Log;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import plugin.core.Registry;
import plugin.service.PluginMenuService;
import plugin.type.Session;

public abstract class PluginMenu<T> {

    public String title = "";
    public String description = "";
    public Session session = null;
    public T state = null;

    public final Instant createdAt = Instant.now();

    public Seq<Seq<HudOption<T>>> options = new Seq<>(new Seq<>());

    public final int getMenuId() {
        return Mathf.random(1000, Integer.MAX_VALUE);
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

    public void build() {
        build(session, state);
    }

    public boolean valid() {
        return true;
    }

    protected abstract void build(Session session, T state);

    public void send(Session session, T state) {
        try {
            @SuppressWarnings("unchecked")
            PluginMenu<T> copy = Registry.createNew(this.getClass());

            copy.title = title;
            copy.description = description;
            copy.session = session;
            copy.state = state;
            copy.options = new Seq<>(new Seq<>());

            var handler = Registry.get(PluginMenuService.class);
            var playerMenus = handler.getPlayerMenus(session.player);
            var exists = playerMenus.find(m -> m == copy);

            if (exists != null) {
                Log.err("Menu @ already in the list", copy);
            }

            if (playerMenus.contains(m -> m.getMenuId() == copy.getMenuId())) {
                Log.warn("Player @ already have menu @", session.player, copy);
                return;
            }

            handler.add(copy);
            handler.showNext(session.player);
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
        return this.getClass().getSimpleName() + "(" + getMenuId() + ")";
    }
}
