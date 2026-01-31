package plugin.commands.client;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import arc.math.Mathf;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.ServerController;
import plugin.commands.PluginCommand;
import plugin.handler.SessionHandler;

public class VnwCommand extends PluginCommand {
    private static int waveVoted = -1;
    private static ScheduledFuture<?> voteTimeout;

    private Param numberParam;

    public VnwCommand() {
        setName("vnw");
        setDescription("Vote for sending a new Wave");
        setAdmin(false);

        numberParam = optional("number").min(1);
    }

    @Override
    public synchronized void handleClient(Player player) {
        var session = SessionHandler.get(player).orElse(null);
        if (session == null)
            return;

        if (session.votedVNW) {
            player.sendMessage("You already voted.");
            return;
        }

        boolean voting = waveVoted != -1;

        if (!voting) {
            waveVoted = numberParam.hasValue() ? numberParam.asInt() : 1;

            voteTimeout = ServerController.BACKGROUND_SCHEDULER.schedule(() -> {
                SessionHandler.each(s -> s.votedVNW = false);
                Call.sendMessage("[scarlet]Vote failed, not enough votes.");
                waveVoted = -1;
            }, 60, TimeUnit.SECONDS);
        }

        session.votedVNW = true;

        int voted = SessionHandler.count(s -> s.votedVNW);
        int required = Mathf.ceil(0.6f * Groups.player.size());

        if (voted < required) {
            Call.sendMessage(Strings.format(
                    "@[orange] voted to send a new wave. [lightgray](@ votes missing)",
                    player.name,
                    required - voted));
            return;
        }

        voteTimeout.cancel(false);
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

        ServerController.BACKGROUND_SCHEDULER.schedule(() -> sendWave(wave - 1), 1, TimeUnit.SECONDS);
    }
}
