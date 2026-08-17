package plugin.afk;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.gen.Call;
import mindustry.net.Administration.PlayerAction;
import plugin.Cfg.OnOfficial;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.annotations.PlayerActionFilter;
import plugin.annotations.Schedule;
import plugin.session.SessionService;
import plugin.session.Session.AfkState;
import plugin.utils.Tr;

@Component
@ConditionOn(OnOfficial.class)
@RequiredArgsConstructor
public class AfkDetector {

    private static final Duration AFK_TIMEOUT = Duration.ofMinutes(5);

    private final SessionService sessionService;

    @PlayerActionFilter
    Boolean onlyAllowLoggedUserToUseLogic(PlayerAction action) {
        sessionService.get(action.player).ifPresent(session -> session.lastActionTime = Instant.now());

        return true;
    }

    @Listener
    public void playerChatEvent(PlayerChatEvent event) {
        sessionService.get(event.player).ifPresent(session -> session.lastActionTime = Instant.now());
    }

    @Listener
    public void onTap(TapEvent event) {
        sessionService.get(event.player)
                .ifPresent(session -> {
                    session.lastActionTime = Instant.now();
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
            boolean hasClicked = Duration.between(session.lastActionTime, Instant.now()).toSeconds() < 30;
            boolean isNotDoingAnything = !hasMoved && !hasClicked;

            if (isNotDoingAnything) {
                if (session.afkState == AfkState.AFK) {
                    return;
                }

                if (session.afkState == AfkState.POTENTIAL_AFK && Duration
                        .between(session.lastPotentialAfkTime, Instant.now()).toSeconds() >= AFK_TIMEOUT.toSeconds()) {
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
            Call.label(session.player.con, Tr.t(session, "afk.label"), 1.1f, session.player.x(), session.player.y());
        });
    }
}
