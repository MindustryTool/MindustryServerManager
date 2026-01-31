package plugin.commands.client;

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
    private static boolean isPreparingForNewWave = false;
    private static int waveVoted = -1;

    private Param numberParam;

    public VnwCommand() {
        setName("vnw");
        setDescription("Vote for sending a new Wave");
        setAdmin(false);

        numberParam = optional("number").min(1);
    }

    @Override
    public void handleClient(Player player) {
        var session = SessionHandler.get(player).orElse(null);

        if (session == null) {
            return;
        }

        if (isPreparingForNewWave) {
            player.sendMessage("Sending waves!");
            return;
        }

        if (session.votedVNW) {
            player.sendMessage("You have Voted already.");
            return;
        }

        boolean isVoting = waveVoted != -1;

        if (isVoting == false) {
            ServerController.BACKGROUND_SCHEDULER.schedule(() -> {
                SessionHandler.each(s -> s.votedVNW = false);
                Call.sendMessage("[scarlet]Failed to vote for new waves, not enough votes.");
                waveVoted = -1;
            }, 60, TimeUnit.SECONDS);
        }

        if (numberParam.hasValue()) {
            if (isVoting) {
                player.sendMessage("A vote to skip wave is already in progress!");
                return;
            } else {
                waveVoted = numberParam.asInt();
            }
        } else {
            waveVoted = 1;
        }

        session.votedVNW = true;

        int votedCount = SessionHandler.count(p -> p.votedVNW);

        int requiredCount = Mathf.ceil(0.6f * Groups.player.size());

        if (votedCount < requiredCount) {
            Call.sendMessage(Strings.format(
                    "@[orange] has voted to skip [green]@ waves. [lightgray](@ votes missing)",
                    player.name,
                    waveVoted,
                    requiredCount - votedCount));
        } else {
            Call.sendMessage("[green]Vote for sending a new wave is passed. New wave will be spawned.");
            SessionHandler.each(s -> s.votedVNW = false);
            sendWave(waveVoted);
        }
    }

    private void sendWave(int wave) {
        isPreparingForNewWave = true;

        if (wave <= 0) {
            isPreparingForNewWave = false;
            return;
        }

        Vars.state.wavetime = 0f;

        ServerController.BACKGROUND_SCHEDULER.schedule(() -> sendWave(wave - 1), 1, TimeUnit.SECONDS);

    }
}
