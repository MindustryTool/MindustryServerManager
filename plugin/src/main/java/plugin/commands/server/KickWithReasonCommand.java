package plugin.commands.server;

import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Packets.KickReason;
import plugin.commands.PluginCommand;

public class KickWithReasonCommand extends PluginCommand {
    private Param idParam;
    private Param reasonParam;

    public KickWithReasonCommand() {
        setName("kickWithReason");
        setDescription("Kick player.");
        idParam = required("id");
        reasonParam = variadic("message");
    }

    @Override
    public void handleServer() {
        if (!Vars.state.isGame()) {
            Log.err("Not hosting. Host a game first.");
            return;
        }

        String uuid = idParam.asString();
        String reason = reasonParam.asString();

        Player target = Groups.player.find(p -> p.uuid().equals(uuid));

        if (target != null) {
            if (reason == null || reason.trim().isEmpty()) {
                target.kick(KickReason.kick);
            } else {
                target.kick(reason);
            }
            Call.sendMessage("[scarlet]" + target.name() + "[scarlet] has been kicked by the server.");
            Log.info("It is done.");
        } else {
            Log.info("Nobody with that uuid could be found: " + uuid);
        }
    }
}
