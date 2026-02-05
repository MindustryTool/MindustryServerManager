package plugin.handler;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.func.Boolf;
import arc.func.Cons;
import arc.util.Log;
import arc.util.Strings;
import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.TimedKillc;
import plugin.Component;
import plugin.IComponent;
import plugin.PluginEvents;
import plugin.Control;
import plugin.event.PlayerKillUnitEvent;
import plugin.event.SessionCreatedEvent;
import plugin.event.SessionRemovedEvent;
import plugin.type.Session;
import plugin.utils.ExpUtils;
import plugin.utils.RankUtils;
import plugin.utils.Utils;
import plugin.repository.SessionRepository;

@Component
@RequiredArgsConstructor
public class SessionHandler implements IComponent {
    private final ConcurrentHashMap<String, Session> data = new ConcurrentHashMap<>();

    private final SessionRepository sessionRepository;

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
            sessionRepository.markDirty(event.getPlayer().uuid());
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

        // PluginEvents.run(PluginUnloadEvent.class, this::unload); // Handled by
        // destroy
    }

    public ConcurrentHashMap<String, Session> get() {
        return data;
    }

    private void create() {
        Core.app.post(() -> Groups.player.each(this::put));
    }

    private void update() {
        each(s -> {
            s.update();
            sessionRepository.markDirty(s.player.uuid());
        });
    }

    @Override
    public void destroy() {
        each(s -> sessionRepository.remove(s.player.uuid()));
        each(s -> s.reset());

        data.clear();

        Log.info("Session handler cleared");
    }

    public Optional<Session> getByUuid(String uuid) {
        return Optional.ofNullable(find(p -> p.player.uuid().equals(uuid)));
    }

    public Optional<Session> get(Player p) {
        return Optional.ofNullable(data.get(p.uuid()));
    }

    public Session put(Player p) {
        return data.computeIfAbsent(p.uuid(), (k) -> {
            var session = new Session(p);

            try {
                var data = session.data();

                data.name = p.name;
                data.lastSaved = session.joinedAt;
                session.update();

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
