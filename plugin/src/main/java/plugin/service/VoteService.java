package plugin.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import arc.Events;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Listener;
import plugin.core.Scheduler;
import plugin.event.SessionRemovedEvent;
import plugin.utils.Utils;

@Component
public class VoteService {
    public final ConcurrentHashMap<String, Seq<String>> votes = new ConcurrentHashMap<>();
    public double ratio = 0.6;
    public Map lastMap = null;

    private final Scheduler scheduler;
    private ScheduledFuture<?> voteTimeout;
    private ScheduledFuture<?> voteCountDown;

    public VoteService(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Listener
    public void onSessionRemovedEvent(SessionRemovedEvent event) {
        removeVote(event.session.player);
        check();
    }

    @Destroy
    public void destroy() {
        votes.clear();
        lastMap = null;
        cancelTimers();
    }

    private void check() {
        votes.forEach((mapId, _v) -> check(mapId));
    }

    public void reset() {
        votes.clear();
        lastMap = null;
        cancelTimers();
    }

    private void cancelTimers() {
        if (voteTimeout != null) {
            voteTimeout.cancel(true);
            voteTimeout = null;
        }

        if (voteCountDown != null) {
            voteCountDown.cancel(true);
            voteCountDown = null;
        }
    }

    public void vote(Player player, String mapId) {
        if (player.admin) {
            var map = getMaps().find(m -> m.file.nameWithoutExtension().equals(mapId));

            if (map == null) {
                player.sendMessage("Map not found.");
                return;
            }

            Vars.maps.setNextMapOverride(map);
            Events.fire(new EventType.GameOverEvent(Team.crux));
            reset();
            return;
        }

        var vote = votes.get(mapId);

        if (vote == null) {
            vote = new Seq<>();
            votes.put(mapId, vote);
        }

        if (voteTimeout == null || voteTimeout.isDone()) {
            startTimeout();
        }

        vote.add(player.uuid());

        check(mapId);
    }

    private void startTimeout() {
        voteTimeout = scheduler.schedule(() -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[scarlet]", "@RTV failed, not enough votes.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            reset();
        }, 60, TimeUnit.SECONDS);

        startCountDown(50);
    }

    private void startCountDown(int time) {
        if (time <= 0) {
            return;
        }

        voteCountDown = scheduler.schedule(() -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[orange]", "@RTV timeout in", " ", time, " ",
                        "@seconds.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            startCountDown(time - 10);
        }, 10, TimeUnit.SECONDS);
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
