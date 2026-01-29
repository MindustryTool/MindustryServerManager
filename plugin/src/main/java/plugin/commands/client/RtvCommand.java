package plugin.commands.client;

import arc.struct.Seq;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.maps.Map;
import plugin.commands.PluginCommand;
import plugin.handler.VoteHandler;

public class RtvCommand extends PluginCommand {
    private Param mapIdParam;

    public RtvCommand() {
        setName("rtv");
        setDescription("Vote to change map (map id in /maps)");
        mapIdParam = required("mapId");
    }

    @Override
    public void handleClient(Player player) {
        String mapIdStr = mapIdParam.asString();
        int mapId;

        try {
            mapId = Integer.parseInt(mapIdStr);
        } catch (NumberFormatException e) {
            player.sendMessage("[red]Map id must be a number");
            return;
        }

        Seq<Map> maps = VoteHandler.getMaps();

        if (mapId < 0 || mapId > (maps.size - 1)) {
            player.sendMessage("[red]Invalid map id");
            return;
        }
        if (VoteHandler.isVoted(player, mapId)) {
            Call.sendMessage("[red]RTV: " + player.name + " [accent]removed their vote for [yellow]"
                    + maps.get(mapId).name());
            VoteHandler.removeVote(player, mapId);
            return;
        }

        VoteHandler.vote(player, mapId);
        Call.sendMessage("[red]RTV: [accent]" + player.name() + " [white]Want to change map to [yellow]"
                + maps.get(mapId).name());
        Call.sendMessage("[red]RTV: [white]Current Vote for [yellow]" + maps.get(mapId).name() + "[white]: [green]"
                + VoteHandler.getVoteCount(mapId) + "/"
                + VoteHandler.getRequire());
        Call.sendMessage("[red]RTV: [white]Use [yellow]/rtv " + mapId + " [white]to add your vote to this map !");
        VoteHandler.check(mapId);
    }
}
