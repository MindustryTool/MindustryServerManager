package plugin.grief;

import plugin.session.SessionService;

import plugin.utils.Tr;

import plugin.gateway.ApiGateway;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.Events;
import arc.math.Mathf;
import events.ServerEvents;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.game.EventType.ConnectionEvent;
import mindustry.game.EventType.PlayerBanEvent;
import mindustry.gen.Groups;
import mindustry.net.Administration.PlayerInfo;
import mindustry.net.Packets.Connect;
import mindustry.net.Packets.KickReason;
import plugin.Cfg;
import plugin.Control;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.core.Scheduler;
import plugin.event.KickEvent;
import plugin.session.SessionRemovedEvent;
import plugin.session.Session;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class AdminService {

    private final Cache<String, Instant> lastGriefReportTimes = Caffeine.newBuilder()
            .expireAfterWrite(Cfg.GRIEF_REPORT_COOLDOWN, TimeUnit.SECONDS)
            .build();

    private Session reported = null;
    private Session reporter = null;
    private ScheduledFuture<?> voteTimeout;

    private final ApiGateway apiGateway;
    private final SessionService sessionService;
    private final Scheduler scheduler;

    @Init
    public void init() {
        Vars.net.handleServer(Connect.class, (con, connect) -> {
            Events.fire(new ConnectionEvent(con));

            if (Vars.netServer.admins.isIPBanned(connect.addressTCP)
                    || Vars.netServer.admins.isSubnetBanned(connect.addressTCP)) {

                con.kick(Tr.t(java.util.Locale.ENGLISH, "grief.banned", "discord", Cfg.DISCORD_INVITE_URL));
            }
        });
    }

    @Listener
    public void onBanEvent(PlayerBanEvent event) {
        ServerEvents.PlayerBanEvent banEvent = new ServerEvents.PlayerBanEvent(Control.SERVER_ID, event.player.ip(),
                event.uuid, event.player.name);
        apiGateway.fire(banEvent);
    }

    @Listener
    public void onKickEvent(KickEvent event) {
        PlayerInfo playerInfo = Vars.netServer.admins.findByIP(event.address);
        String name = playerInfo == null ? "Unknown Player" : playerInfo.lastName;
        ServerEvents.PlayerKickEvent kickEvent = new ServerEvents.PlayerKickEvent(Control.SERVER_ID, event.address,
                event.uuid, name, event.reason);
        apiGateway.fire(kickEvent);
    }

    @Listener
    public void onSessionRemoved(SessionRemovedEvent event) {
        if (reported == event.session) {
            if (reporter != null) {
                lastGriefReportTimes.invalidate(reporter);
            }
            reported.player.kick(KickReason.kick, 1000 * 60);
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
            session.player.sendMessage(Tr.t(session.locale, "grief.no_report"));
            return;
        }

        if (session.votedGrief) {
            session.player.sendMessage(Tr.t(session.locale, "grief.already_voted"));
            return;
        }

        session.votedGrief = true;

        int voted = sessionService.count(s -> s.votedGrief && !s.isAfk());
        int required = Math.max(1, Mathf.ceil(0.6f * sessionService.countActive()));

        if (voted >= required) {
            reported.player.kick(KickReason.kick);
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "grief.kicked", "player", reported.player.name);
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
                String msg = Tr.t(locale, "grief.voted_for",
                        "voter", session.player.name, "target", reported.player.name);
                for (var p : players) {
                    p.sendMessage(msg);
                }
            });
        }
    }

    public void reportGrief(Session player, Session target) {
        if (Groups.player.size() < 3) {
            player.player.sendMessage(Tr.t(player.locale, "grief.need_more_players"));
            return;
        }

        if (target == player) {
            player.player.sendMessage(Tr.t(player.locale, "grief.report_self"));
            return;
        }

        Instant lastReportTime = lastGriefReportTimes.getIfPresent(player);

        if (lastReportTime != null) {
            if (lastReportTime.plusSeconds(Cfg.GRIEF_REPORT_COOLDOWN).isAfter(Instant.now())) {
                long remaining = lastReportTime.plusSeconds(Cfg.GRIEF_REPORT_COOLDOWN)
                        .getEpochSecond() - Instant.now().getEpochSecond();

                player.player.sendMessage(Tr.t(player.locale, "grief.cooldown", "seconds", remaining));
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
            String msg = Tr.t(locale, "grief.reported",
                    "reporter", player.player.name, "target", target.player.name);
            for (var p : players) {
                p.sendMessage(msg);
            }
        });

        voteTimeout = scheduler.schedule(() -> {
            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "grief.vote_failed");
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

        sessionService.each(s -> s.votedGrief = false);
    }
}
