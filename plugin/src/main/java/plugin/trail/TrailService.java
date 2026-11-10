package plugin.trail;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import arc.func.Cons2;
import arc.graphics.Color;
import arc.struct.Seq;
import lombok.Data;
import lombok.Getter;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.gen.Call;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.annotations.MainThread;
import plugin.annotations.Schedule;
import plugin.core.Registry;
import plugin.money.MoneyService;
import plugin.session.Session;
import plugin.session.SessionRepository;
import plugin.session.SessionService;

@Component
public class TrailService {

    public final ConcurrentHashMap<String, Trail> trails = new ConcurrentHashMap<>();
    private final SessionService sessionService;
    private final SessionRepository sessionRepository;
    private MoneyService moneyService;

    public TrailService(SessionService sessionService, SessionRepository sessionRepository) {
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
    }

    public void setMoneyService(MoneyService moneyService) {
        this.moneyService = moneyService;
    }

    public MoneyService resolveMoneyService() {
        if (moneyService != null) {
            return moneyService;
        }
        return Registry.getOrNull(MoneyService.class);
    }

    @Init
    public void init() {
        Trail.create(this, "shock", 500, Fx.landShock);
        Trail.create(this, "lightning", 1000, Fx.lightningShoot);
        Trail.create(this, "fire", 2000, Fx.fire);
        Trail.create(this, "heal", 3500, Fx.healWave);
        Trail.custom(this, "placeblock", 5000, (x, y) -> Call.effect(Fx.placeBlock, x, y, 2.5f, Color.white));
        Trail.create(this, "explotion", 0, Fx.explosion, TrailRequirement.admin());
    }

    @MainThread
    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    private void render() {
        sessionService.each(session -> {
            var userTrail = session.getData().trail;
            if (userTrail != null && !userTrail.isEmpty()) {
                var trail = trails.get(userTrail);
                if (trail != null && trail.allowed(session) && session.player.unit() != null
                        && session.player.unit().isValid()) {
                    trail.render.get(session.player.unit().x, session.player.unit().y);
                }
            }
        });
    }

    public boolean purchase(Session session, Trail trail) {
        if (trail.owns(session)) {
            return true;
        }
        MoneyService ms = resolveMoneyService();
        if (ms != null && ms.withdraw(session, trail.getPrice())) {
            if (session.getData().unlockedTrails == null) {
                session.getData().unlockedTrails = new ArrayList<>();
            }
            if (!session.getData().unlockedTrails.contains(trail.getName())) {
                session.getData().unlockedTrails.add(trail.getName());
            }
            if (sessionRepository != null) {
                sessionRepository.markDirty(session);
            }
            return true;
        }
        return false;
    }

    @Data
    public static final class Trail {
        private final TrailService handler;
        private final String name;
        private final long price;
        private final Seq<TrailRequirement> requirements;
        private final Cons2<Float, Float> render;

        public boolean owns(Session session) {
            if (handler != null && handler.resolveMoneyService() == null) {
                return true;
            }
            if (price <= 0 && requirements.isEmpty()) {
                return true;
            }
            if (session.isAdmin() || session.player.admin) {
                return true;
            }
            return session.getData().unlockedTrails != null && session.getData().unlockedTrails.contains(name);
        }

        public boolean allowed(Session session) {
            if (session.isAdmin() || session.player.admin) {
                return true;
            }
            if (requirements.size > 0 && !requirements.allMatch(r -> r.getAllowed().apply(session))) {
                return false;
            }
            return owns(session);
        }

        public static void create(TrailService handler, String name, long price, Effect effect,
                TrailRequirement... requirements) {
            var trail = new Trail(handler, name, price, Seq.with(requirements),
                    (x, y) -> Call.effect(effect, x, y, 0, Color.white));
            handler.trails.put(name, trail);
        }

        public static void custom(TrailService handler, String name, long price, Cons2<Float, Float> render,
                TrailRequirement... requirements) {
            var trail = new Trail(handler, name, price, Seq.with(requirements), render);
            handler.trails.put(name, trail);
        }
    }

    @Getter
    public static class TrailRequirement {
        private final String message;
        private final Function<Session, Boolean> allowed;

        private TrailRequirement(String message, Function<Session, Boolean> allowed) {
            this.message = message;
            this.allowed = allowed;
        }

        public static TrailRequirement admin() {
            return new TrailRequirement("@You must be an admin to use this trail",
                    session -> session.isAdmin() || session.player.admin);
        }
    }
}
