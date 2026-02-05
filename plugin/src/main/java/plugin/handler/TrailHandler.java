package plugin.handler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import arc.func.Cons2;
import arc.graphics.Color;
import arc.struct.Seq;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustry.content.Fx;
import mindustry.entities.Effect;
import mindustry.gen.Call;
import plugin.Component;
import plugin.IComponent;
import plugin.Control;
import plugin.type.Session;
import plugin.utils.ExpUtils;

@Component
@RequiredArgsConstructor
public class TrailHandler implements IComponent {

    public final ConcurrentHashMap<String, Trail> trails = new ConcurrentHashMap<>();
    private final SessionHandler sessionHandler;

    @Override
    public void init() {
        Trail.create(this, "shock", Fx.landShock, TrailRequirement.level(2));
        Trail.create(this, "lightning", Fx.chainLightning, TrailRequirement.level(5));
        Trail.create(this, "fire", Fx.fire, TrailRequirement.level(15));
        Trail.create(this, "heal", Fx.healWave, TrailRequirement.level(35));
        Trail.create(this, "placeblock", Fx.placeBlock, TrailRequirement.level(50));
        Trail.create(this, "explotion", Fx.explosion, TrailRequirement.admin());

        Control.BACKGROUND_SCHEDULER
                .scheduleAtFixedRate(this::render, 0, 500, TimeUnit.MILLISECONDS);
    }

    private void render() {
        sessionHandler.each(session -> {
            var userTrail = session.data().trail;
            if (userTrail != null) {
                var trail = trails.get(userTrail);
                if (trail != null && trail.allowed(session)) {
                    trail.render.get(session.player.x, session.player.y);
                }
            }
        });
    }

    @Data
    public static final class Trail {
        private final String name;
        private final Seq<TrailRequirement> requirements;
        private final Cons2<Float, Float> render;

        public boolean allowed(Session session) {
            return requirements.allMatch(r -> r.getAllowed().apply(session));
        }

        public static void create(TrailHandler handler, String name, Effect effect, TrailRequirement... requirements) {
            var trail = new Trail(name, Seq.with(requirements), (x, y) -> Call.effect(effect, x, y, 0, Color.white));
            handler.trails.put(name, trail);
        }

        public static void custom(TrailHandler handler, String name, Cons2<Float, Float> render,
                TrailRequirement... requirements) {
            var trail = new Trail(name, Seq.with(requirements), render);
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

        public static TrailRequirement level(int level) {
            return new TrailRequirement("@Level >= " + level, session -> ExpUtils.getLevel(session) >= level);
        }

        public static TrailRequirement admin() {
            return new TrailRequirement("@You must be an admin to use this trail", session -> session.player.admin);
        }

    }

}
