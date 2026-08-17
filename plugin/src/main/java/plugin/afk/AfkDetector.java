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

@Component
@RequiredArgsConstructor
public class AfkDetector {

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

    @Schedule(fixedDelay = 5, unit = TimeUnit.SECONDS)
    public void detectAfk() {
        sessionService.each(session -> {
            boolean hasMoved = session.lastX != session.player.x() || session.lastY != session.player.y();
            session.lastX = session.player.x();
            session.lastY = session.player.y();
            boolean hasClicked = Duration.between(Instant.now(), session.lastClickTime).toSeconds() < 5;
            session.isAfk = !hasMoved && !hasClicked;
        });
    }

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    public void updateAfkLabel() {
        sessionService.each(session -> session.isAfk, session -> {
            Call.label("Afk", 1, session.player.x, session.player.y);
        });
    }
}
