package plugin.handler;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.func.Boolf;
import arc.func.Cons;
import arc.util.Log;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.PluginEvents;
import plugin.ServerController;
import plugin.event.PlayerKillUnitEvent;
import plugin.type.Session;
import plugin.type.SessionData;
import plugin.utils.JsonUtils;

public class SessionHandler {
    private static final ConcurrentHashMap<String, Session> data = new ConcurrentHashMap<>();

    public static ConcurrentHashMap<String, Session> get() {
        return data;
    }

    public static void init() {
        Groups.player.each(p -> {
            Log.info(p);
            put(p);
        });

        PluginEvents.on(PlayerKillUnitEvent.class, event -> {
            get(event.getPlayer()).ifPresent(session -> session.addKill(event.getUnit().type, 1));
        });

        ServerController.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(SessionHandler::create, 10, 10, TimeUnit.SECONDS);
        ServerController.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(SessionHandler::update, 0, 10, TimeUnit.SECONDS);
    }

    private static void create() {
        Groups.player.each(SessionHandler::put);
    }

    private static void update() {
        each(Session::update);
    }

    public static void clear() {
        each(s -> writeSessionData(s.player, s.data));
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

    public static void put(Player p) {
        data.putIfAbsent(p.uuid(), new Session(p, readSessionData(p)));
    }

    public static SessionData readSessionData(Player p) {
        SessionData pdata = new SessionData();

        try {
            if (Core.settings.has(p.uuid())) {
                pdata = JsonUtils.readJsonAsClass(Core.settings.getString(p.uuid()), SessionData.class);
            }
        } catch (Exception e) {
            Log.err("Error while loading session data for player @: @", p.name, e);
            Core.settings.remove(p.uuid());
        }

        return pdata;
    }

    public static void writeSessionData(Player p, SessionData pdata) {
        try {
            Core.settings.put(p.uuid(), JsonUtils.toJsonString(pdata));
        } catch (Exception e) {
            Log.err("Error while saving session data for player @: @", p.name, e);
        }
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
