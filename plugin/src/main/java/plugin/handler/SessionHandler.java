package plugin.handler;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.func.Boolf;
import arc.func.Cons;
import arc.util.Log;
import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.TimedKillc;
import plugin.Component;
import plugin.Control;
import plugin.IComponent;
import plugin.PluginEvents;
import plugin.event.PlayerKillUnitEvent;
import plugin.event.SessionCreatedEvent;
import plugin.event.SessionRemovedEvent;
import plugin.repository.SessionRepository;
import plugin.service.SessionService;
import plugin.type.Session;
import plugin.type.SessionData;
import plugin.utils.RankUtils;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class SessionHandler implements IComponent {
    private final ConcurrentHashMap<String, Session> data = new ConcurrentHashMap<>();

    private final SessionRepository sessionRepository;
    private final SessionService sessionService;

    @Override
    public void init() {
        Core.app.post(() -> Groups.player.each(this::put));

        Control.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(this::create, 10, 2, TimeUnit.SECONDS);
        Control.BACKGROUND_SCHEDULER.scheduleWithFixedDelay(this::update, 0, 1, TimeUnit.SECONDS);

        PluginEvents.on(PlayerKillUnitEvent.class, event -> {
            get(event.getPlayer()).ifPresent(session -> {
                if (event.getUnit().type.isHidden() || event.getUnit().type instanceof TimedKillc) {
                    return;
                }

                sessionService.addKill(session, event.getUnit().type, 1);
            });
        });

        PluginEvents.on(PlayerLeave.class, event -> {
            remove(event.player);
        });

        PluginEvents.on(PlayerJoin.class, event -> {
            Core.app.post(() -> put(event.player));

            Control.ioTask("Send Leader Board",
                    () -> event.player.sendMessage(RankUtils.getRankString(Utils.parseLocale(event.player.locale),
                            sessionRepository.getLeaderBoard(10))));
        });
    }

    public ConcurrentHashMap<String, Session> get() {
        return data;
    }

    private void create() {
        Core.app.post(() -> Groups.player.each(this::put));
    }

    private void update() {
        each(sessionService::update);
    }

    @Override
    public void destroy() {
        each(s -> sessionRepository.remove(s.player.uuid()));
        each(s -> s.reset());

        data.clear();
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

                sessionService.update(session);

            } catch (Exception e) {
                Log.err(e);
            }

            Core.app.post(() -> PluginEvents.fire(new SessionCreatedEvent(session)));

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
}
