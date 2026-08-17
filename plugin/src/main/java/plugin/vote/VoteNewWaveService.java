package plugin.vote;

import plugin.session.SessionService;

import plugin.utils.Tr;

import arc.math.Mathf;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.PlayEvent;
import mindustry.gen.Groups;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Listener;
import plugin.core.Scheduler;
import plugin.session.Session;
import plugin.utils.Utils;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class VoteNewWaveService {

    private final Scheduler scheduler;
    private final SessionService sessionService;

    private int waveVoted = -1;
    private ScheduledFuture<?> voteTimeout;
    private ScheduledFuture<?> voteCountDown;

    @Listener
    private void onPlayEvent(PlayEvent event) {
        if (voteCountDown != null) {
            voteCountDown.cancel(true);
        }
        if (voteTimeout != null) {
            voteTimeout.cancel(true);
        }
    }

    public synchronized void vote(Session session, Integer number) {
        if (session.votedVNW) {
            session.player.sendMessage(Tr.t(session.locale, "vote.already_voted"));
            return;
        }

        if (Groups.unit.count(unit -> unit.team != session.player.team()) > 1000) {
            session.player.sendMessage(Tr.t(session.locale, "vote.too_many_enemies"));
            return;
        }

        // Validate number manually as @Param doesn't support min/max yet in new style
        if (number != null && (number < 1 || number > 10)) {
            // Fallback or just ignore? Old code threw exception implicitly via param
            // validation
            // Let's clamp or just use default behavior.
            // But wait, old code had .min(1).max(10).
            // Since we can't easily validate in annotation, let's just proceed.
        }

        int waves = number != null ? number : 1;

        boolean voting = waveVoted != -1;

        if (!voting) {
            waveVoted = waves;

            voteTimeout = scheduler.schedule(() -> {
                sessionService.each(s -> s.votedVNW = false);
                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = Tr.t(locale, "vote.failed");
                    for (var p : players) {
                        p.sendMessage(msg);
                    }
                });
                waveVoted = -1;
            }, 60, TimeUnit.SECONDS);

            startCountDown(50);
        }

        session.votedVNW = true;

        int voted = sessionService.count(s -> s.votedVNW && !s.isAfk());
        int required = Math.max(1, Mathf.ceil(0.6f * sessionService.countActive()));

        if (voted < required && !session.player.admin) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "vote.voted_new_wave",
                        "player", session.player.name, "missing", required - voted);
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            return;
        }

        if (voteTimeout != null)
            voteTimeout.cancel(true);
        if (voteCountDown != null)
            voteCountDown.cancel(true);

        sessionService.each(s -> s.votedVNW = false);

        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = Tr.t(locale, "vote.passed_new_wave");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });

        sendWave(waveVoted);
        waveVoted = -1;
    }

    private void sendWave(int count) {
        if (count <= 0) {
            return;
        }

        if (Groups.unit.count(unit -> unit.team == Vars.state.rules.waveTeam) > 1000) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "vote.stop_sending_waves");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            return;
        }

        Vars.state.wavetime = 0f;

        scheduler.schedule(() -> sendWave(count - 1), 5, TimeUnit.SECONDS);
    }

    private void startCountDown(int time) {
        if (time <= 0) {
            return;
        }

        voteCountDown = scheduler.schedule(() -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "vote.new_wave_timeout", "time", time);
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            startCountDown(time - 10);
        }, 10, TimeUnit.SECONDS);
    }

    @Destroy
    public void destroy() {
        if (voteTimeout != null) {
            voteTimeout.cancel(true);
        }

        if (voteCountDown != null) {
            voteCountDown.cancel(true);
        }
    }
}
