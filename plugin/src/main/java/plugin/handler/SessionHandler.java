package plugin.handler;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.func.Boolf;
import arc.func.Cons;
import arc.util.Log;
import arc.util.Strings;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.event.PlayerKillUnitEvent;
import plugin.event.PluginUnloadEvent;
import plugin.event.SessionCreatedEvent;
import plugin.event.SessionRemovedEvent;
import plugin.type.Session;
import plugin.utils.ExpUtils;
import plugin.utils.RankUtils;
import plugin.utils.Utils;
import plugin.repository.SessionRepository;

public class SessionHandler {
    private static final ConcurrentHashMap<String, Session> data = new ConcurrentHashMap<>();

    public static ConcurrentHashMap<String, Session> get() {
        return data;
    }

    public static void init() {
        Core.app.post(() -> Groups.player.each(SessionHandler::put));

        ServerControl.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(SessionHandler::create, 10, 2, TimeUnit.SECONDS);
        ServerControl.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(SessionHandler::update, 0, 1, TimeUnit.SECONDS);

        PluginEvents.on(PlayerKillUnitEvent.class, event -> {
            get(event.getPlayer()).ifPresent(session -> {

                long result = session.addKill(event.getUnit().type, 1);
                long base = 1;

                while (result >= base * 10) {
                    base *= 10;
                }

                long print = (result / base) * base;

                if (result != print || result < 10) {
                    return;
                }

                Utils.forEachPlayerLocale((locale, players) -> {
                    String formatted = Strings.format(" @ @ (+@exp)",
                            print,
                            Utils.icon(event.getUnit().type),
                            ExpUtils.unitHealthToExp(result * event.getUnit().type.health));

                    String translated = I18n.t(locale, event.getPlayer().name, " ", "@killed", formatted);

                    for (var player : players) {
                        player.sendMessage(translated);
                    }
                });
            });
            SessionRepository.markDirty(event.getPlayer().uuid());
        });

        PluginEvents.on(PlayerLeave.class, event -> {
            remove(event.player);
        });

        PluginEvents.on(PlayerJoin.class, event -> {
            Core.app.post(() -> put(event.player));
            ServerControl.backgroundTask("Send Leader Board",
                    () -> event.player.sendMessage(RankUtils.getRankString(SessionRepository.getLeaderBoard(10))));
        });

        PluginEvents.run(PluginUnloadEvent.class, SessionHandler::unload);
    }

    private static void create() {
        Core.app.post(() -> Groups.player.each(SessionHandler::put));
    }

    private static void update() {
        each(s -> {
            s.update();
            SessionRepository.markDirty(s.player.uuid());
        });
    }

    public static void unload() {
        each(s -> SessionRepository.remove(s.player.uuid()));
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
        return data.computeIfAbsent(p.uuid(), (k) -> {
            var session = new Session(p);

            ServerControl.backgroundTask("Update Session", () -> {
                try {
                    var data = session.data();

                    data.joinedAt = session.joinedAt;
                    data.name = session.originalName;
                } catch (Exception e) {
                    Log.err(e);
                }
                session.update();
            });

            Core.app.post(() -> PluginEvents.fire(new SessionCreatedEvent(session)));

            return session;
        });
    }

    public static void remove(Player p) {
        var previous = data.remove(p.uuid());

        if (previous != null) {
            previous.data().playTime += Instant.now().toEpochMilli() - previous.joinedAt;

            PluginEvents.fire(new SessionRemovedEvent(previous));
        }
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
