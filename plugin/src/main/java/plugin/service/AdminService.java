package plugin.service;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.Events;
import arc.math.Mathf;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.game.EventType.ConnectionEvent;
import mindustry.gen.Groups;
import mindustry.net.Packets.Connect;
import mindustry.net.Packets.KickReason;
import plugin.Config;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.Control;
import plugin.event.SessionRemovedEvent;
import plugin.type.Session;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class AdminService {

    private final Cache<String, Instant> lastGriefReportTimes = Caffeine.newBuilder()
            .expireAfterWrite(Config.GRIEF_REPORT_COOLDOWN, TimeUnit.SECONDS)
            .build();

    private Session reported = null;
    private Session reporter = null;
    private ScheduledFuture<?> voteTimeout;

    private final SessionHandler sessionHandler;

    @Init
    public void init() {
        Vars.net.handleServer(Connect.class, (con, connect) -> {
            Events.fire(new ConnectionEvent(con));

            if (Vars.netServer.admins.isIPBanned(connect.addressTCP)
                    || Vars.netServer.admins.isSubnetBanned(connect.addressTCP)) {

                con.kick("[scarlet]You has been banned from the server\n" +
                        "If you think this is a mistake, please contact the server administrator\n" +
                        "Discord: " + Config.DISCORD_INVITE_URL);
            }
        });
    }

    @Listener
    public void onSessionRemoved(SessionRemovedEvent event) {
        if (reported == event.session) {
            if (reporter != null) {
                lastGriefReportTimes.invalidate(reporter);
            }
            reported.player.kick(KickReason.kick, 60 * 1000 * 60);
            reset();
        }
    }

    @Destroy
    public void destroy() {
        reset();
        lastGriefReportTimes.invalidateAll();
    }

    public boolean isGriefVoting() {
        return reported != null;
    }

    public void voteGrief(Session session) {
        if (reported == null) {
            session.player.sendMessage(I18n.t(session.locale,
                    "@No player is being reported."));
            return;
        }

        if (session.votedGrief) {
            session.player.sendMessage(I18n.t(session.locale,
                    "@You already voted."));
            return;
        }

        session.votedGrief = true;

        int voted = sessionHandler.count(s -> s.votedGrief);
        int required = Mathf.ceil(0.6f * Groups.player.size());

        if (voted >= required) {
            reported.player.kick(KickReason.kick);
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[red]", "@Player ", reported.player.name,
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
                String msg = I18n.t(locale, "[red]", "@Player ", session.player.name,
                        " ", "@voted for player ", reported.player.name,
                        " ", "@for griefing. Use ", "/grief", " ", "@to kick this player.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
        }
    }

    public void reportGrief(Session player, Session target) {
        if (Groups.player.size() < 3) {
            player.player.sendMessage(I18n.t(player.locale,
                    "@You cannot report grief if there are less than 3 players."));
            return;
        }

        if (target == player) {
            player.player.sendMessage(I18n.t(player.locale,
                    "@You cannot report yourself.(Bud are you alright)"));
            return;
        }

        Instant lastReportTime = lastGriefReportTimes.getIfPresent(player);

        if (lastReportTime != null) {
            if (lastReportTime.plusSeconds(Config.GRIEF_REPORT_COOLDOWN).isAfter(Instant.now())) {
                long remaining = lastReportTime.plusSeconds(Config.GRIEF_REPORT_COOLDOWN)
                        .getEpochSecond() - Instant.now().getEpochSecond();

                player.player.sendMessage(I18n.t(player.locale,
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
            String msg = I18n.t(locale, "[red]", "@Player ", player.player.name, " ",
                    "@reported player ", target.player.name, " ", "@for griefing. Use ", "/grief", " ",
                    "@to kick this player.");
            for (var p : players) {
                p.sendMessage(msg);
            }
        });

        voteTimeout = Control.SCHEDULER.schedule(() -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = I18n.t(locale, "[scarlet]", "@Vote failed, not enough votes.");
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
            target.player.team(originalTeam);
            reset();
        }, 60, TimeUnit.SECONDS);
    }

    private void reset() {
        reported = null;
        reporter = null;

        if (voteTimeout != null) {
            voteTimeout.cancel(true);
        }

        sessionHandler.each(s -> s.votedGrief = false);
    }
}
