package plugin.commands.client;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import arc.math.Mathf;
import mindustry.Vars;
import mindustry.gen.Groups;
import plugin.ServerControl;
import plugin.commands.PluginCommand;
import plugin.handler.I18n;
import plugin.handler.SessionHandler;
import plugin.type.Session;
import plugin.utils.Utils;

public class VnwCommand extends PluginCommand {
    private static int waveVoted = -1;
    private static ScheduledFuture<?> voteTimeout;
    private static ScheduledFuture<?> voteCountDown;

    private Param numberParam;

    public VnwCommand() {
        setName("vnw");
        setDescription("Vote for sending a new Wave");
        setAdmin(false);

        numberParam = optional("number").min(1).max(10);
    }

    @Override
    public synchronized void handleClient(Session session) {
        if (session.votedVNW) {
            session.player.sendMessage(I18n.t(session.locale, "@You already voted."));
            return;
        }

        if (Groups.unit.count(unit -> unit.team != session.player.team()) > 1000) {
            session.player.sendMessage(I18n.t(session.locale,
                    "@You can't vote when there are more than 1000 enemies."));
            return;
        }

        boolean voting = waveVoted != -1;

        if (!voting) {
            waveVoted = numberParam.hasValue() ? numberParam.asInt() : 1;

            voteTimeout = ServerControl.BACKGROUND_SCHEDULER.schedule(() -> {
                SessionHandler.each(s -> s.votedVNW = false);
                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = I18n.t(locale, "[scarlet]", "@Vote failed, not enough votes.");
                    for (var p : players) {
                        p.sendMessage(msg);
                    }
                });
                waveVoted = -1;
            }, 60, TimeUnit.SECONDS);

            startCountDown(50);

        }

        session.votedVNW = true;

        int voted = SessionHandler.count(s -> s.votedVNW);
        int required = Mathf.ceil(0.6f * Groups.player.size());

        if (voted < required && !session.player.admin) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale,
                        session.player.name, "[orange]", " ", "@voted to send a new wave. ", "[lightgray]", "(",
                        required - voted, " ", "@votes missing", ")", " ", "@use", "/vnw", " ", "@to skip waves");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            return;
        }

        voteTimeout.cancel(false);
        voteCountDown.cancel(false);
        SessionHandler.each(s -> s.votedVNW = false);

        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = I18n.t(locale, "[green]", "@Vote passed. Sending new wave.");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });
        sendWave();
    }

    private void sendWave() {
        if (waveVoted <= 0) {
            waveVoted = -1;
            return;
        }

        waveVoted--;

        if (Groups.unit.count(unit -> unit.team == Vars.state.rules.waveTeam) > 1000) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[scarlet]", "@Stop sending waves, more than 1000 enemies.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            return;
        }

        Vars.state.wavetime = 0f;

        ServerControl.BACKGROUND_SCHEDULER.schedule(() -> sendWave(), 1, TimeUnit.SECONDS);
    }

    private void startCountDown(int time) {
        if (time <= 0) {
            return;
        }

        voteCountDown = ServerControl.BACKGROUND_SCHEDULER.schedule(() -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[orange]", "@Vote new wave timeout in", " ", time, " ",
                        "@seconds.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            startCountDown(time - 10);
        }, 10, TimeUnit.SECONDS);
    }
}
