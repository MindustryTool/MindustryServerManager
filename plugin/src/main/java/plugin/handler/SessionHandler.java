package plugin.handler;

import java.util.HashMap;
import java.util.Optional;

import arc.func.Boolf;
import arc.func.Cons;
import arc.util.Log;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.PluginEvents;
import plugin.event.PlayerKillUnitEvent;
import plugin.type.Session;
import plugin.type.SessionData;

public class SessionHandler {
    private static final HashMap<String, Session> data = new HashMap<>();

    public static HashMap<String, Session> get() {
        return data;
    }

    public static void init() {
        Groups.player.each(SessionHandler::put);

        PluginEvents.on(PlayerKillUnitEvent.class, event -> {
            get(event.getPlayer()).ifPresent(session -> session.addKill(event.getUnitType(), 1));
        });
    }

    public static void clear() {
        each(s -> s.reset());

        data.clear();

        Log.info("Session handler cleared");
    }

    public static Optional<Session> getByUuid(String uuid) {
        return Optional.ofNullable(find(p -> p.player.uuid().equals(uuid)));
    }

    public static Optional<Session> get(Player p) {
        return Optional.ofNullable(data.get(p.uuid()));
    }

    public static Session put(Player p) {
        Session data_ = new Session(p, new SessionData());

        data.put(p.uuid(), data_);

        return data_;
    }

    public static void remove(Player p) {
        data.remove(p.uuid());
    }

    public static boolean contains(Player p) {
        return data.containsKey(p.uuid());
    }

    public static void each(Cons<Session> item) {
        data.forEach((k, v) -> item.get(v));
    }

    public static void each(Boolf<Session> pred, Cons<Session> item) {
        data.forEach((k, v) -> {
            if (pred.get(v))
                item.get(v);
        });
    }

    public static int count(Boolf<Session> pred) {
        int size = 0;

        for (Session p : data.values()) {
            if (pred.get(p))
                size++;
        }

        return size;
    }

    public static Session find(Boolf<Session> pred) {
        for (Session p : data.values()) {
            if (pred.get(p))
                return p;
        }
        return null;
    }
}
