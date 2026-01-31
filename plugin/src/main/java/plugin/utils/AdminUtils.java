package plugin.utils;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.math.Mathf;
import mindustry.game.Team;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Packets.KickReason;
import plugin.Config;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.handler.SessionHandler;

public class AdminUtils {

    private static final Cache<Player, Instant> lastGriefReportTimes = Caffeine.newBuilder()
            .expireAfterWrite(Config.GRIEF_REPORT_COOLDOWN, TimeUnit.SECONDS)
            .build();

    private static Player reported = null;
    private static Player reporter = null;
    private static ScheduledFuture<?> voteTimeout;

    public static void init() {
        PluginEvents.on(PlayerLeave.class, event -> {
            if (reported == event.player) {
                if (reporter != null) {
                    lastGriefReportTimes.invalidate(reporter);
                }
                reported.kick(KickReason.kick, 60 * 1000 * 60);
                reset();
            }
        });
    }

    public static void unload() {
        reset();
        lastGriefReportTimes.invalidateAll();
    }

    public static boolean isGriefVoting() {
        return reported != null;
    }

    public static void voteGrief(Player player) {
        if (reported == null) {
            player.sendMessage("No player is being reported.");
            return;
        }

        SessionHandler.get(player).ifPresent(session -> {
            if (session.votedGrief) {
                player.sendMessage("You already voted.");
                return;
            }

            session.votedGrief = true;

            int voted = SessionHandler.count(s -> s.votedGrief);
            int required = Mathf.ceil(0.6f * Groups.player.size());

            if (voted >= required) {
                reported.kick(KickReason.kick);
                Call.sendMessage("[red]Player " + reported.name + " was kicked for griefing.");
                if (reporter != null) {
                    lastGriefReportTimes.invalidate(reporter);
                }
                reset();
            } else {
                Call.sendMessage("[red]Player " + player.name + " voted for player " + reported.name
                        + " for griefing. Use /grief to kick this player.");
            }
        });

    }

    public static void reportGrief(Player player, Player target) {
        if (Groups.player.size() < 3) {
            player.sendMessage("You cannot report grief if there are less than 3 players.");
            return;
        }

        if (target == player) {
            player.sendMessage("You cannot report yourself.(Bud are you alright)");
            return;
        }

        Instant lastReportTime = lastGriefReportTimes.getIfPresent(player);

        if (lastReportTime != null) {
            if (lastReportTime.plusSeconds(Config.GRIEF_REPORT_COOLDOWN).isAfter(Instant.now())) {
                long remaining = lastReportTime.plusSeconds(Config.GRIEF_REPORT_COOLDOWN)
                        .getEpochSecond() - Instant.now().getEpochSecond();

                player.sendMessage("You must wait " + remaining + " seconds to report again.");
                return;
            }
        }

        reported = target;
        reporter = player;
        lastGriefReportTimes.put(player, Instant.now());

        SessionHandler.get(player).ifPresent(session -> {
            session.votedGrief = true;
        });

        var originalTeam = target.team();

        target.team(Team.derelict);

        Call.sendMessage("[red]Player " + player.name + " reported player " + target.name
                + " for griefing. Use /grief to kick this player.");

        voteTimeout = ServerControl.BACKGROUND_SCHEDULER.schedule(() -> {
            Call.sendMessage("[scarlet]Vote failed, not enough votes.");
            target.team(originalTeam);
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
