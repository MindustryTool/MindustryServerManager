package plugin.handler;

import java.util.HashMap;

import arc.Events;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;

public class RtvVoteHandler {
    public HashMap<Integer, Seq<String>> votes = new HashMap<>();
    public double ratio = 0.6;

    public void reset() {
        votes.clear();
    }

    public void vote(Player player, int mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            vote = new Seq<>();
            votes.put(mapId, vote);
        }

        vote.add(player.uuid());
    }

    public void removeVote(Player player, int mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return;
        }

        vote.remove(player.uuid());
    }

    public boolean isVoted(Player player, int mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return false;
        }
        return vote.contains(player.uuid());
    }

    public int getRequire() {
        return (int) Math.min(Math.floor(ratio * Groups.player.size()) + 1, Groups.player.size());
    }

    public int getVoteCount(int mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return 0;
        }
        return vote.size;
    }

    public void removeVote(Player player) {
        for (Seq<String> vote : votes.values()) {
            vote.remove(player.uuid());
        }
    }

    public Seq<Map> getMaps() {
        return Vars.maps.customMaps();
    }

    public void check(int mapId) {
        if (getVoteCount(mapId) >= getRequire()) {
            Call.sendMessage("[red]RTV: [green]Vote passed! Changing map...");
            Vars.maps.setNextMapOverride(getMaps().get(mapId));
            reset();
            Events.fire(new EventType.GameOverEvent(Team.crux));
        }
    }
}
