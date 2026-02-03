package plugin.handler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.graphics.Color;
import arc.struct.Seq;
import mindustry.entities.Effect;
import mindustry.gen.Call;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.event.SessionRemovedEvent;
import plugin.type.Session;
import plugin.type.Trail;

public class TrailHandler {
    private static final ConcurrentHashMap<Session, Trail> playerTrails = new ConcurrentHashMap<>();
    private static final Seq<Trail> trails = new Seq<>();

    public static void init() {
        trails.add(new FirstTrail());

        PluginEvents.on(SessionRemovedEvent.class, event -> playerTrails.remove(event.session));
        ServerControl.BACKGROUND_SCHEDULER.scheduleAtFixedRate(TrailHandler::render, 0, 1, TimeUnit.SECONDS);
    }

    public static void toogle(Session session) {
        if (playerTrails.contains(session)) {
            playerTrails.remove(session);
            session.player.sendMessage(ApiGateway.translate(session.locale,
                    "[scarlet]", "@Trail disabled"));
        } else {
            playerTrails.put(session, trails.first());
            session.player.sendMessage(ApiGateway.translate(session.locale,
                    "[green]", "@Trail enabled"));
        }
    }

    private static void render() {
        playerTrails.forEach((session, trail) -> {
            trail.render(session, session.player.x, session.player.y);
        });
    }

    private static class FirstTrail implements Trail {
        int i = 0;

        @Override
        public boolean isAllowed(Session session) {
            return true;
        }

        @Override
        public void render(Session session, float x, float y) {
            Call.effect(Effect.get(++i % Effect.all.size), x, y, 1, Color.white);
        }
    }
}
