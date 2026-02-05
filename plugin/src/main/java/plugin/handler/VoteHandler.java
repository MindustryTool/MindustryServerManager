package plugin.handler;

import java.util.concurrent.ConcurrentHashMap;

import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;
import plugin.Component;
import plugin.IComponent;
import plugin.PluginEvents;
import plugin.event.SessionRemovedEvent;
import plugin.utils.Utils;

@Component
public class VoteHandler implements IComponent {
    public final ConcurrentHashMap<String, Seq<String>> votes = new ConcurrentHashMap<>();
    public double ratio = 0.6;
    public Map lastMap = null;

    @Override
    public void init() {
        PluginEvents.on(SessionRemovedEvent.class, event -> {
            removeVote(event.session.player);
            check();
        });

        // PluginEvents.run(PluginUnloadEvent.class, this::unload); // Handled by destroy
    }

    @Override
    public void destroy() {
        votes.clear();
        lastMap = null;

        Log.info("Vote handler unloaded");
    }

    private void check() {
        votes.forEach((mapId, _v) -> check(mapId));
    }

    public void reset() {
        votes.clear();
        lastMap = null;
    }

    public void vote(Player player, String mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            vote = new Seq<>();
            votes.put(mapId, vote);
        }

        vote.add(player.uuid());

        check(mapId);
    }

    private void removeVote(Player player, String mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return;
        }

        vote.remove(player.uuid());
    }

    public boolean isVoted(Player player, String mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return false;
        }
        return vote.contains(player.uuid());
    }

    public int getRequire() {
        return (int) Math.min(Math.floor(ratio * Groups.player.size()) + 1, Groups.player.size());
    }

    public int getVoteCount(String mapId) {
        var vote = votes.get(mapId);

        if (vote == null) {
            return 0;
        }
        return vote.size;
    }

    private void removeVote(Player player) {
        for (Seq<String> vote : votes.values()) {
            vote.remove(player.uuid());
        }
    }

    public Seq<Map> getMaps() {
        return Vars.maps.customMaps();
    }

    public void check(String mapId) {
        if (getVoteCount(mapId) >= getRequire()) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[red]RTV: ", "[green]", "@Vote passed! Changing map...");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            var map = getMaps().find(m -> m.file.nameWithoutExtension().equals(mapId));

            if (map == null) {
                Utils.forEachPlayerLocale((locale, players) -> {
                    String msg = I18n.t(locale, "@Map with id: ", mapId, " ", "@not exists");
                    for (var p : players) {
                        p.sendMessage(msg);
                    }
                });
                return;
            }

            Vars.maps.setNextMapOverride(map);
            Events.fire(new EventType.GameOverEvent(Team.crux));
            reset();
        }
    }

    public void handleVote(Player player) {
        if (lastMap != null) {
            handleVote(player, lastMap);
        }
    }

    public void handleVote(Player player, Map map) {
        String mapId = map.file.nameWithoutExtension();

        if (isVoted(player, mapId)) {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[red]RTV: ", player.name, " ", "[accent]",
                        "@removed their vote for ", "[yellow]", map.name());
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            removeVote(player, mapId);
            return;
        }

        lastMap = map;

        vote(player, mapId);

        Utils.forEachPlayerLocale((locale, players) -> {
            String msg1 = I18n.t(locale, "[red]RTV: ", "[accent] ", player.name(), " ", "[white] ",
                    "@Want to change map to ", "[yellow]", map.name());
            String msg2 = I18n.t(locale, "[red]RTV: ", "[white]", "@Current Vote for ", "[yellow]",
                    map.name() + "[white]: ", "[green]", getVoteCount(mapId), "/", getRequire());
            String msg3 = I18n.t(locale, "[red]RTV: ", "[white]", "@Use ", "[yellow] ", "/rtv yes",
                    " ", "@to add your vote to this map !");
            for (var p : players) {
                p.sendMessage(msg1);
                p.sendMessage(msg2);
                p.sendMessage(msg3);
            }
        });

    }
}
