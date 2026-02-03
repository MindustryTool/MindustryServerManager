package plugin.commands.client;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import arc.math.Mathf;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import plugin.ServerControl;
import plugin.commands.PluginCommand;
import plugin.handler.SessionHandler;
import plugin.type.Session;

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
            session.player.sendMessage("You already voted.");
            return;
        }

        boolean voting = waveVoted != -1;

        if (!voting) {
            waveVoted = numberParam.hasValue() ? numberParam.asInt() : 1;

            voteTimeout = ServerControl.BACKGROUND_SCHEDULER.schedule(() -> {
                SessionHandler.each(s -> s.votedVNW = false);
                Call.sendMessage("[scarlet]Vote failed, not enough votes.");
                waveVoted = -1;
            }, 60, TimeUnit.SECONDS);

            startCountDown(50);

        }

        session.votedVNW = true;

        int voted = SessionHandler.count(s -> s.votedVNW);
        int required = Mathf.ceil(0.6f * Groups.player.size());

        if (voted < required) {
            Call.sendMessage(Strings.format(
                    "@[orange] voted to send a new wave. [lightgray](@ votes missing)",
                    session.player.name,
                    required - voted));
            return;
        }

        voteTimeout.cancel(false);
        voteCountDown.cancel(false);
        SessionHandler.each(s -> s.votedVNW = false);

        Call.sendMessage("[green]Vote passed. Sending new wave.");
        sendWave(waveVoted);
    }

    private void sendWave(int wave) {
        if (wave <= 0) {
            waveVoted = -1;
            return;
        }

        Vars.state.wavetime = 0f;

        ServerControl.BACKGROUND_SCHEDULER.schedule(() -> sendWave(wave - 1), 1, TimeUnit.SECONDS);
    }

    private void startCountDown(int time) {
        if (time <= 0) {
            return;
        }

        voteCountDown = ServerControl.BACKGROUND_SCHEDULER.schedule(() -> {
            Call.sendMessage(Strings.format("[orange]Vote new wave timeout in @ seconds.", time));
            startCountDown(time - 10);
        }, 10, TimeUnit.SECONDS);
    }
}
