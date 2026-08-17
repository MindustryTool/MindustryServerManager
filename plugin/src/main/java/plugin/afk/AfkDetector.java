package plugin.afk;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.TapEvent;
import mindustry.gen.Call;
import plugin.annotations.Component;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.session.SessionService;
import plugin.session.Session.AfkState;

@Component
@RequiredArgsConstructor
public class AfkDetector {

    private static final Duration AFK_TIMEOUT = Duration.ofMinutes(1);

    private final SessionService sessionService;

    @Listener
    public void onTap(TapEvent event) {
        sessionService.get(event.player)
                .ifPresent(session -> {
                    session.lastClickTime = Instant.now();
                    session.lastClickX = event.tile.getX();
                    session.lastClickY = event.tile.getY();
                });
    }

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    public void detectAfk() {
        sessionService.each(session -> {
            boolean hasMoved = session.lastX != session.player.x() || session.lastY != session.player.y();
            session.lastX = session.player.x();
            session.lastY = session.player.y();
            boolean hasClicked = Duration.between(session.lastClickTime, Instant.now()).abs().toMillis() < AFK_TIMEOUT.toMillis();
            boolean isNotDoingAnything = !hasMoved && !hasClicked;

            if (isNotDoingAnything) {
                if (session.afkState == AfkState.AFK) {
                    return;
                }

                if (session.afkState == AfkState.POTENTIAL_AFK && Duration.between(session.lastPotentialAfkTime, Instant.now()).abs().toSeconds() >= 30) {
                    session.afkState = AfkState.AFK;
                    session.lastPotentialAfkTime = Instant.now();
                } else if (session.afkState == AfkState.ACTIVE) {
                    session.afkState = AfkState.POTENTIAL_AFK;
                    session.lastPotentialAfkTime = Instant.now();
                }
            } else {
                session.afkState = AfkState.ACTIVE;
            }
        });
    }

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    public void updateAfkLabel() {
        sessionService.each(session -> session.afkState == AfkState.AFK, session -> {
            Call.label("Afk", 1.1f, session.player.x(), session.player.y());
        });
    }
}
