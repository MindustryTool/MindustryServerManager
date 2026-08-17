package plugin.session;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import arc.func.Boolf;
import arc.func.Cons;
import arc.util.Log;
import dto.LoginDto;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import plugin.PluginEvents;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.utils.I18n;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class SessionService {
    private final ConcurrentHashMap<String, Session> data = new ConcurrentHashMap<>();

    private final SessionRepository sessionRepository;

    public Function<Session, Integer> getLevel = session -> {
        SessionData data = session.getData();

        // Using existing logic for compatibility: calculating exp based on session play
        // time
        long currentSessionTime = session.sessionPlayTime();
        long calculatedExp = ExpUtils.getTotalExp(data, currentSessionTime);

        int level = ExpUtils.levelFromTotalExp(calculatedExp);

        return level;
    };

    @Listener
    public void onPlayerJoin(PlayerJoin event) {
        put(event.player);
    }

    @Listener
    public void onPlayerLeave(PlayerLeave event) {
        remove(event.player);
    }

    public ConcurrentHashMap<String, Session> get() {
        return data;
    }

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    private void update() {
        each(this::update);
    }

    @Destroy
    public void destroy() {
        each(s -> sessionRepository.remove(s.player.uuid()));
        each(Session::reset);

        data.clear();
    }

    public void update(Session session) {
        int level = getLevel.apply(session);

        if (level != session.currentLevel) {
            if (session.currentLevel != 0) {
                int oldLevel = session.currentLevel;
                int newLevel = level;

                if (level > session.currentLevel) {
                    Tasks.io("Update level", () -> {
                        Utils.forEachPlayerLocale((locale, players) -> {
                            String message = SessionUtils.getLevelUpMessage(locale, oldLevel, newLevel);
                            players.forEach(p -> p.sendMessage(session.player.name + message));
                        });
                    });
                }
            }

            session.currentLevel = level;
            session.player.name(SessionUtils.getPlayerName(session));
        }

        sessionRepository.markDirty(session.player.uuid());
    }

    public Optional<Session> getByUuid(String uuid) {
        return Optional.ofNullable(find(p -> p.player.uuid().equals(uuid)));
    }

    public Optional<Session> get(Player p) {
        return Optional.ofNullable(data.get(p.uuid()));
    }

    public Session put(Player p) {
        return data.computeIfAbsent(p.uuid(), (k) -> {
            // Fetch data first
            SessionData sessionData = sessionRepository.get(p);
            var session = new Session(p, sessionData);

            try {
                // Initialize session data
                synchronized (sessionData) {
                    sessionData.name = p.name;
                    sessionData.lastSaved = session.joinedAt;
                }

                update(session);

            } catch (Exception e) {
                Log.err(e);
            }

            PluginEvents.fire(new SessionCreatedEvent(session));

            return session;
        });
    }

    public void remove(Player p) {
        var previous = data.remove(p.uuid());

        if (previous != null) {
            PluginEvents.fire(new SessionRemovedEvent(previous));
        }
    }

    public boolean contains(Player p) {
        return data.containsKey(p.uuid());
    }

    public int size() {
        return data.size();
    }

    public void each(Cons<Session> item) {
        data.forEach((k, v) -> item.get(v));
    }

    public void each(Boolf<Session> pred, Cons<Session> item) {
        data.forEach((k, v) -> {
            if (pred.get(v))
                item.get(v);
        });
    }

    public int count(Boolf<Session> pred) {
        int size = 0;

        for (Session p : data.values()) {
            if (pred.get(p))
                size++;
        }

        return size;
    }

    public Session find(Boolf<Session> pred) {
        for (Session p : data.values()) {
            if (pred.get(p))
                return p;
        }
        return null;
    }

    public void setLogin(Session session, LoginDto login) {
        PlayerInfo target = Vars.netServer.admins.getInfoOptional(session.player.uuid());

        if (target != null) {
            Player playert = Groups.player.find(p -> p.getInfo() == target);

            if (login.getIsAdmin()) {
                Vars.netServer.admins.adminPlayer(target.id, playert == null ? target.adminUsid : playert.usid());
            } else {
                Vars.netServer.admins.unAdminPlayer(target.id);
            }
        } else {
            session.player.admin = false;
            Log.info("Player @ is no longer an admin", session.player.name);
        }

        session.login = login;
        session.player.admin = false;
        session.player.name(SessionUtils.getPlayerName(session));

        if (login.getIsAdmin()) {
            session.player.sendMessage(I18n.t(session, "[accent]", "@Use /admin to toogle admin"));
        }
    }
}