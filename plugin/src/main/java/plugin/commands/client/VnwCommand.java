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
    private static short waveVoted = 0;

    private Param numberParam;

    public VnwCommand() {
        setName("vnw");
        setDescription("Vote for sending a New Wave");
        numberParam = optional("number");
    }

    @Override
    public void handleClient(Player player) {
        var session = SessionHandler.get(player);

        if (Groups.player.size() < 3 && !player.admin) {
            player.sendMessage("[scarlet]3 players are required or be an admin to start a vote.");
            return;

        } else if (session.votedVNW) {
            player.sendMessage("You have Voted already.");
            return;
        }

        String arg = numberParam.asString();

        if (arg != null) {
            if (!isPreparingForNewWave) {
                if (player.admin) {
                    if (Strings.canParseInt(arg)) {
                        waveVoted = (short) Strings.parseInt(arg);
                    } else {
                        player.sendMessage("Please select number of wave want to skip");
                        return;
                    }

                }
            } else {
                player.sendMessage("A vote to skip wave is already in progress!");
                return;
            }
        } else if (!isPreparingForNewWave) {
            waveVoted = 1;
        }

        session.votedVNW = true;
        int cur = SessionHandler.count(p -> p.votedVNW),
                req = Mathf.ceil(0.6f * Groups.player.size());
        Call.sendMessage(player.name + "[orange] has voted to "
                + (waveVoted == 1 ? "send a new wave" : "skip [green]" + waveVoted + " waves") + ". [lightgray]("
                + (req - cur) + " votes missing)");

        if (!isPreparingForNewWave)
            ServerController.BACKGROUND_SCHEDULER.schedule(() -> {
                Call.sendMessage("[scarlet]Vote for "
                        + (waveVoted == 1 ? "sending a new wave"
                                : "skipping [scarlet]" + waveVoted + "[] waves")
                        + " failed! []Not enough votes.");
                waveVoted = 0;
            }, 60, TimeUnit.SECONDS);

        if (cur < req)
            return;

        Call.sendMessage("[green]Vote for "
                + (waveVoted == 1 ? "sending a new wave" : "skiping [scarlet]" + waveVoted + "[] waves")
                + " is Passed. New Wave will be Spawned.");

        if (waveVoted > 0) {
            while (waveVoted-- > 0) {
                try {
                    Vars.state.wavetime = 0f;
                    Thread.sleep(30);
                } catch (Exception e) {
                    break;
                }
            }

        } else
            Vars.state.wave += waveVoted;
    }
}
