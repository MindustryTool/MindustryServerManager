package plugin.handler;

import java.util.HashMap;
import arc.func.Boolf;
import arc.func.Cons;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.type.Session;

public class SessionHandler {
    private static final HashMap<String, Session> data = new HashMap<>();

    public static HashMap<String, Session> get() {
        return data;
    }

    public static void load() {
        Groups.player.each(SessionHandler::put);
    }

    public static void clear() {
        data.clear();
    }

    public static Session getByID(int id) {
        return find(p -> p.playerId == id);
    }

    public static Session getByUuid(String uuid) {
        return find(p -> p.playerUuid.equals(uuid));
    }

    public static Session get(Player p) {
        if (p == null)
            return null;
        return data.get(p.uuid());
    }

    public static Session put(Player p) {
        Session data_ = new Session(p);

        data.put(p.uuid(), data_);

        return data_;
    }

    public static Session remove(Player p) {
        Session data_ = get(p);

        data.remove(p.uuid());

        return data_;
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
