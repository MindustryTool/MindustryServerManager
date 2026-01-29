package plugin.commands.client;

import arc.struct.Seq;
import mindustry.gen.Player;
import mindustry.maps.Map;
import plugin.commands.PluginCommand;
import plugin.handler.VoteHandler;

public class MapsCommand extends PluginCommand {
    private Param pageParam;

    public MapsCommand() {
        setName("maps");
        setDescription("Display available maps");
        pageParam = optional("page");
    }

    @Override
    public void handleClient(Player player) {
        final int MAPS_PER_PAGE = 10;
        Seq<Map> maps = VoteHandler.getMaps();
        int page = 1;
        int maxPage = maps.size / MAPS_PER_PAGE + (maps.size % MAPS_PER_PAGE == 0 ? 0 : 1);

        String pageStr = pageParam.asString();
        if (pageStr != null) {
            try {
                page = Integer.parseInt(pageStr);
            } catch (NumberFormatException e) {
                player.sendMessage("[red]Page must be a number");
                return;
            }
        }

        if (page < 1 || page > maxPage) {
            player.sendMessage("[red]Invalid page");
            return;
        }

        player.sendMessage("[green]Available maps: [white](" + page + "/" + maxPage + ")");

        for (int i = 0; i < MAPS_PER_PAGE; i++) {
            int mapId = (page - 1) * MAPS_PER_PAGE + i;
            if (mapId > maps.size - 1) {
                break;
            }
            player.sendMessage("[green]" + mapId + " [white]- [yellow]" + maps.get(mapId).name());
        }
    }
}
