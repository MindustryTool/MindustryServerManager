package plugin.session;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import arc.func.Boolf;
import arc.func.Cons;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import dto.LoginDto;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.net.Administration.PlayerInfo;
import plugin.Cfg;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.session.SessionRepository.RankData;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class SessionService {
    private final ConcurrentHashMap<String, Session> data = new ConcurrentHashMap<>();

    private final SessionRepository sessionRepository;

    private Seq<RankData> leaderboardCache = new Seq<>();

    public Function<Session, Integer> getLevel = session -> {
        SessionData data = session.getData();

        return ExpUtils.levelFromTotalExp((long) data.exp);
    };

    public Function<Session, String> getPlayerName = (Session session) -> {
        boolean hasColor = session.currentLevel > Cfg.COLOR_NAME_LEVEL || session.player.admin;
        String playerName = hasColor ? session.getData().name : Strings.stripColors(session.getData().name);
        Locale locale = Utils.parseLocale(session.player.locale);
        String languageOrRank = locale.getLanguage().toUpperCase();

        if (languageOrRank.isEmpty()) {
            languageOrRank = session.player.locale;
        }

        languageOrRank = "[" + languageOrRank + "]";

        for (int i = 0; i < leaderboardCache.size; i++) {
            if (leaderboardCache.get(i).uuid.equals(session.player.uuid())) {
                if (i == 0) {
                    languageOrRank = "[gold][1st][]";
                } else if (i == 1) {
                    languageOrRank = "[#C0C0C0][2nd][]";
                } else if (i == 2) {
                    languageOrRank = "[#CD7F32][3rd][]";
                } else {
                    languageOrRank = "[" + (i + 1) + "th]";
                }
                break;
            }
        }

        String status = session.isLoggedIn() ? String.valueOf(Iconc.ok) : "";

        return status
                + languageOrRank + " " + "<" + "[accent]" + session.currentLevel + "[white]> "
                + playerName;
    };

    @Init
    public void init() {
        int count = Vars.netServer.admins.playerInfo.size;
        Vars.netServer.admins.playerInfo.values().toSeq().removeAll(info -> info.timesJoined <= 0);
        Vars.netServer.admins.save();
        int removed = count - Vars.netServer.admins.playerInfo.size;
        if (removed > 0) {
            Log.info("Removed @ invalid players", removed);
        }
    }

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

    @Schedule(fixedDelay = 1, unit = TimeUnit.MINUTES)
    private void updateLeaderboardData() {
        if (Vars.state.isPlaying()) {
            leaderboardCache = sessionRepository.leaderBoard(3);
        }
    }

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    private void tick() {
        each(s -> PluginEvents.fire(new ExpGainEvent(s, 1)));
    }

    @Destroy
    public void destroy() {
        each(s -> sessionRepository.remove(s.player.uuid()));
        each(Session::reset);

        data.clear();
    }

    @Listener
    public void onLevelUp(LevelUpEvent event) {
        Session session = event.session;
        int lastLevel = event.lastLevel;
        int newLevel = event.newLevel;

        Utils.forEachPlayerLocale((locale, players) -> {
            String message = SessionUtils.getLevelUpMessage(locale, lastLevel, newLevel);
            players.forEach(p -> p.sendMessage(session.player.name + message));
        });
    }

    @Schedule(fixedRate = 1, unit = TimeUnit.MINUTES)
    public void reduceExpGainBonusWhenAfk() {
        each(session -> {
            if (session.isAfk()) {
                session.expGainBonus -= 0.01f;
                if (session.expGainBonus < 0) {
                    session.expGainBonus = 0;
                }
            }
        });
    }

    @Listener
    public void onExpGain(ExpGainEvent event) {
        Session session = event.session;
        SessionData data = session.getData();

        synchronized (data) {
            data.exp += event.amount + session.expGainBonus * event.amount;
        }

        sessionRepository.markDirty(session);
        updateLevel(session);
    }

    public void updateLevel(Session session) {
        int level = getLevel.apply(session);

        if (level != session.currentLevel) {
            if (session.currentLevel != 0) {
                int oldLevel = session.currentLevel;
                int newLevel = level;

                if (level > session.currentLevel) {
                    PluginEvents.fire(new LevelUpEvent(session, oldLevel, newLevel));
                }
            }

            session.currentLevel = level;
        }
        session.player.name(getPlayerName.apply(session));
        sessionRepository.markDirty(session);
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
                    sessionData.locale = p.locale;
                }

                updateLevel(session);

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

    public int countActive() {
        return count(s -> !s.isAfk());
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
        session.player.name(getPlayerName.apply(session));

        if (login.getIsAdmin()) {
            session.player.sendMessage(Tr.t(session, "session.use_admin"));
        }
    }
}
