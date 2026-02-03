package plugin.handler;

import java.util.concurrent.ConcurrentHashMap;

import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;
import plugin.PluginEvents;
import plugin.event.PluginUnloadEvent;
import plugin.event.SessionRemovedEvent;

public class VoteHandler {
    public static ConcurrentHashMap<String, Seq<String>> votes = new ConcurrentHashMap<>();
    public static double ratio = 0.6;
    public static Map lastMap = null;

    public static void init() {
        PluginEvents.on(SessionRemovedEvent.class, event -> {
            removeVote(event.session.player);
            check();
        });

        PluginEvents.run(PluginUnloadEvent.class, VoteHandler::unload);
    }

    private static void unload() {
        votes.clear();
        votes = null;

        Log.info("Vote handler unloaded");
    }

    private static void check() {
        votes.forEach((mapId, _v) -> check(mapId));
    }

    public static void reset() {
        votes.clear();
        lastMap = null;
    }

    public static void vote(Player player, String mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            vote = new Seq<>();
            votes.put(mapId, vote);
        }

        vote.add(player.uuid());

        check(mapId);
    }

    private static void removeVote(Player player, String mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return;
        }

        vote.remove(player.uuid());
    }

    public static boolean isVoted(Player player, String mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return false;
        }
        return vote.contains(player.uuid());
    }

    public static int getRequire() {
        return (int) Math.min(Math.floor(ratio * Groups.player.size()) + 1, Groups.player.size());
    }

    public static int getVoteCount(String mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return 0;
        }
        return vote.size;
    }

    private static void removeVote(Player player) {
        for (Seq<String> vote : votes.values()) {
            vote.remove(player.uuid());
        }
    }

    public static Seq<Map> getMaps() {
        return Vars.maps.customMaps();
    }

    public static void check(String mapId) {
        if (getVoteCount(mapId) >= getRequire()) {
            Call.sendMessage("[red]RTV: [green]Vote passed! Changing map...");
            var map = getMaps().find(m -> m.file.nameWithoutExtension().equals(mapId));

            if (map == null) {
                Call.sendMessage("Map with id: " + mapId + " not exists");
                return;
            }

            Vars.maps.setNextMapOverride(map);
            Events.fire(new EventType.GameOverEvent(Team.crux));
            reset();
        }
    }

    public static void handleVote(Player player) {
        if (lastMap != null) {
            handleVote(player, lastMap);
        }
    }

    public static void handleVote(Player player, Map map) {
        String mapId = map.file.nameWithoutExtension();

        if (isVoted(player, mapId)) {
            Call.sendMessage("[red]RTV: " + player.name + " [accent]removed their vote for [yellow]"
                    + map.name());
            removeVote(player, mapId);
            return;
        }

        lastMap = map;

        vote(player, mapId);

        Call.sendMessage(
                "[red]RTV: [accent]" + player.name() + " [white]Want to change map to [yellow]" + map.name());
        Call.sendMessage(
                "[red]RTV: [white]Current Vote for [yellow]" + map.name() + "[white]: [green]"
                        + getVoteCount(mapId) + "/"
                        + getRequire());
        Call.sendMessage(
                "[red]RTV: [white]Use [yellow]/rtv yes to add your vote to this map !");

    }
}
