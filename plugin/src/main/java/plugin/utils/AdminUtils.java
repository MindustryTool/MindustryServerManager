package plugin.utils;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.math.Mathf;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.net.Packets.KickReason;
import plugin.Config;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.event.PluginUnloadEvent;
import plugin.event.SessionRemovedEvent;
import plugin.handler.SessionHandler;
import plugin.type.Session;
import plugin.handler.ApiGateway;

public class AdminUtils {

    private static final Cache<String, Instant> lastGriefReportTimes = Caffeine.newBuilder()
            .expireAfterWrite(Config.GRIEF_REPORT_COOLDOWN, TimeUnit.SECONDS)
            .build();

    private static Session reported = null;
    private static Session reporter = null;
    private static ScheduledFuture<?> voteTimeout;

    public static void init() {
        PluginEvents.on(SessionRemovedEvent.class, event -> {
            if (reported == event.session) {
                if (reporter != null) {
                    lastGriefReportTimes.invalidate(reporter);
                }
                reported.player.kick(KickReason.kick, 60 * 1000 * 60);
                reset();
            }
        });

        PluginEvents.run(PluginUnloadEvent.class, AdminUtils::unload);
    }

    private static void unload() {
        reset();
        lastGriefReportTimes.invalidateAll();
    }

    public static boolean isGriefVoting() {
        return reported != null;
    }

    public static void voteGrief(Session session) {
        if (reported == null) {
            session.player.sendMessage(ApiGateway.translate(session.locale,
                    "@No player is being reported."));
            return;
        }

        if (session.votedGrief) {
            session.player.sendMessage(ApiGateway.translate(session.locale,
                    "@You already voted."));
            return;
        }

        session.votedGrief = true;

        int voted = SessionHandler.count(s -> s.votedGrief);
        int required = Mathf.ceil(0.6f * Groups.player.size());

        if (voted >= required) {
            reported.player.kick(KickReason.kick);
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = ApiGateway.translate(locale, "[red]", "@Player ", reported.player.name,
                        " ", "@was kicked for griefing.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            if (reporter != null) {
                lastGriefReportTimes.invalidate(reporter);
            }
            reset();
        } else {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = ApiGateway.translate(locale, "[red]", "@Player ", session.player.name,
                        " ", "@voted for player ", reported.player.name,
                        " ", "@for griefing. Use ", "/grief", " ", "@to kick this player.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
        }
    }

    public static void reportGrief(Session player, Session target) {
        if (Groups.player.size() < 3) {
            player.player.sendMessage(ApiGateway.translate(player.locale,
                    "@You cannot report grief if there are less than 3 players."));
            return;
        }

        if (target == player) {
            player.player.sendMessage(ApiGateway.translate(player.locale,
                    "@You cannot report yourself.(Bud are you alright)"));
            return;
        }

        Instant lastReportTime = lastGriefReportTimes.getIfPresent(player);

        if (lastReportTime != null) {
            if (lastReportTime.plusSeconds(Config.GRIEF_REPORT_COOLDOWN).isAfter(Instant.now())) {
                long remaining = lastReportTime.plusSeconds(Config.GRIEF_REPORT_COOLDOWN)
                        .getEpochSecond() - Instant.now().getEpochSecond();

                player.player.sendMessage(ApiGateway.translate(player.locale,
                        "@You must wait ", remaining, " ", "@seconds to report again."));
                return;
            }
        }

        reported = target;
        reporter = player;
        lastGriefReportTimes.put(player.player.uuid(), Instant.now());

        player.votedGrief = true;

        var originalTeam = target.player.team();

        target.player.team(Team.derelict);

        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = ApiGateway.translate(locale, "[red]", "@Player ", player.player.name, " ",
                    "@reported player ", target.player.name, " ", "@for griefing. Use ", "/grief", " ",
                    "@to kick this player.");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });

        voteTimeout = ServerControl.BACKGROUND_SCHEDULER.schedule(() -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = ApiGateway.translate(locale, "[scarlet]", "@Vote failed, not enough votes.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            target.player.team(originalTeam);
            reset();
        }, 60, TimeUnit.SECONDS);
    }

    private static void reset() {
        reported = null;
        reporter = null;

        if (voteTimeout != null) {
            voteTimeout.cancel(true);
        }

        SessionHandler.each(s -> s.votedGrief = false);
    }
}
