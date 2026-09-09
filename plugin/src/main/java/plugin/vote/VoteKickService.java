package plugin.vote;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import arc.util.Log;
import events.ServerEvents;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration.Config;
import mindustry.net.Packets.KickReason;
import plugin.Control;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Listener;
import plugin.core.Scheduler;
import plugin.gateway.ApiGateway;
import plugin.session.LoginMenu;
import plugin.session.SessionRemovedEvent;
import plugin.session.SessionService;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class VoteKickService {

    public static final int KICK_DURATION_SECONDS = 60 * 60; // 60 minutes
    public static final int VOTE_DURATION_SECONDS = 30; // 30 seconds
    public static final int VOTE_COOLDOWN_SECONDS = 60 * 5; // 5 minutes
    public static final int MIN_PLAYERS = 3;

    private final Scheduler scheduler;
    private final SessionService sessionService;
    private final ApiGateway apiGateway;

    private final ConcurrentHashMap<String, Instant> cooldowns = new ConcurrentHashMap<>();

    @Getter
    private VoteKickSession currentSession = null;
    private ScheduledFuture<?> timeoutTask = null;

    public static enum Vote {
        YES,
        NO
    }

    public static class VoteKickSession {
        public final Player target;
        public final Player initiator;
        public final String reason;
        public final Instant startTime = Instant.now();
        public final ConcurrentHashMap<String, Vote> voted = new ConcurrentHashMap<>();

        public VoteKickSession(Player target, Player initiator, String reason) {
            this.target = target;
            this.initiator = initiator;
            this.reason = reason;
        }
    }

    public boolean isVoting() {
        return currentSession != null;
    }

    public int getRemainingSeconds() {
        if (currentSession == null) {
            return 0;
        }
        long elapsed = Instant.now().getEpochSecond() - currentSession.startTime.getEpochSecond();
        return Math.max(0, (int) (VOTE_DURATION_SECONDS - elapsed));
    }

    public int getVotes() {
        if (currentSession == null) {
            return 0;
        }
        int sum = 0;

        for (Player player : Groups.player) {
            Vote vote = currentSession.voted.get(player.uuid());
            if (vote != null && vote == Vote.YES) {
                var sessionOpt = sessionService.get(player);
                if (sessionOpt.isPresent() && sessionOpt.get().isAfk()) {
                    continue;
                }
                sum++;
            }
        }
        return sum;
    }

    public int getVotesRequired() {
        if (currentSession == null) {
            return 2;
        }

        int activeCount = 0;

        for (Player player : Groups.player) {
            if (Vars.state.rules.pvp && player.team() != currentSession.target.team()) {
                continue;
            }

            if (player == currentSession.target) {
                continue;
            }

            var sessionOpt = sessionService.get(player);
            if (sessionOpt.isPresent() && !sessionOpt.get().isAfk()) {
                activeCount++;
            }
        }
        return Math.max(2, 2 + (activeCount > 4 ? 1 : 0));
    }

    public synchronized boolean startVote(Player initiator, Player target, String reason) {
        var sessionOpt = sessionService.get(initiator);
        if (sessionOpt.isEmpty() || !sessionOpt.get().isLoggedIn()) {
            initiator.sendMessage(Tr.t(initiator, "votekick.login_required"));
            if (sessionOpt.isPresent()) {
                new LoginMenu().send(sessionOpt.get(), null);
            }
            return false;
        }

        if (!Config.enableVotekick.bool()) {
            initiator.sendMessage(Tr.t(initiator, "votekick.disabled"));
            return false;
        }

        if (Groups.player.size() < MIN_PLAYERS) {
            initiator.sendMessage(Tr.t(initiator, "votekick.need_more_players"));
            return false;
        }

        if (initiator.isLocal()) {
            initiator.sendMessage(Tr.t(initiator, "votekick.local_player"));
            return false;
        }

        if (isVoting()) {
            initiator.sendMessage(Tr.t(initiator, "votekick.vote_in_progress"));
            return false;
        }

        if (target == null) {
            initiator.sendMessage(Tr.t(initiator, "votekick.no_player_found", "name", ""));
            return false;
        }

        if (target == initiator) {
            initiator.sendMessage(Tr.t(initiator, "votekick.kick_self"));
            return false;
        }

        if (target.admin) {
            initiator.sendMessage(Tr.t(initiator, "votekick.kick_admin"));
            return false;
        }

        if (target.isLocal()) {
            initiator.sendMessage(Tr.t(initiator, "votekick.local_player"));
            return false;
        }

        if (Vars.state.rules.pvp && target.team() != initiator.team()) {
            initiator.sendMessage(Tr.t(initiator, "votekick.team_only"));
            return false;
        }

        Instant lastVote = cooldowns.get(initiator.uuid());
        if (lastVote != null) {
            long elapsedSeconds = Instant.now().getEpochSecond() - lastVote.getEpochSecond();
            if (elapsedSeconds < VOTE_COOLDOWN_SECONDS) {
                long remainingMinutes = Math.max(1, (VOTE_COOLDOWN_SECONDS - elapsedSeconds + 59) / 60);
                initiator.sendMessage(Tr.t(initiator, "votekick.cooldown", "minutes", remainingMinutes));
                return false;
            }
        }

        cooldowns.put(initiator.uuid(), Instant.now());

        currentSession = new VoteKickSession(target, initiator, reason);
        currentSession.voted.put(initiator.uuid(), Vote.YES);

        int currentVotes = getVotes();
        int required = getVotesRequired();

        broadcastToEligible(target, (locale, players) -> {
            String msg1 = Tr.t(locale, "votekick.vote_started",
                    "initiator", initiator.name, "target", target.name,
                    "votes", currentVotes, "required", required);
            String msg2 = Tr.t(locale, "votekick.vote_reason", "reason", reason);
            String msg3 = Tr.t(locale, "votekick.vote_hint");
            for (Player p : players) {
                p.sendMessage(msg1);
                p.sendMessage(msg2);
                p.sendMessage(msg3);
            }
        });

        dispatchPromptMenu(target);

        timeoutTask = scheduler.schedule(() -> {
            synchronized (VoteKickService.this) {
                if (currentSession != null && currentSession.target == target) {
                    if (!checkPass()) {
                        broadcastToEligible(target, (locale, players) -> {
                            String msg = Tr.t(locale, "votekick.vote_failed", "target", target.name);
                            for (Player p : players) {
                                p.sendMessage(msg);
                            }
                        });
                        reset();
                    }
                }
            }
        }, VOTE_DURATION_SECONDS, TimeUnit.SECONDS);

        checkPass();
        return true;
    }

    private void dispatchPromptMenu(Player target) {
        for (Player p : Groups.player) {
            if (p == target || p.isLocal() || (Vars.state.rules.pvp && p.team() != target.team())) {
                continue;
            }
            var sessionOpt = sessionService.get(p);
            if (sessionOpt.isPresent()) {
                new VotePromptMenu().send(sessionOpt.get(), null);
            }
        }
    }

    public synchronized void vote(Player player, Vote vote) {
        if (currentSession == null) {
            player.sendMessage(Tr.t(player, "votekick.no_vote_in_progress"));
            return;
        }

        if (player.isLocal()) {
            player.sendMessage(Tr.t(player, "votekick.local_player"));
            return;
        }

        if (player == currentSession.target) {
            player.sendMessage(Tr.t(player, "votekick.own_trial"));
            return;
        }

        if (Vars.state.rules.pvp && player.team() != currentSession.target.team()) {
            player.sendMessage(Tr.t(player, "votekick.team_vote_only"));
            return;
        }

        Vote previousVote = currentSession.voted.get(player.uuid());
        if (previousVote != null && previousVote == vote) {
            player.sendMessage(Tr.t(player, "votekick.already_voted", "sign", vote == Vote.YES ? "yes" : "no"));
            return;
        }

        currentSession.voted.put(player.uuid(), vote);

        int currentVotes = getVotes();
        int required = getVotesRequired();

        broadcastToEligible(currentSession.target, (locale, players) -> {
            String msg = Tr.t(locale, "votekick.vote_voted",
                    "player", player.name, "target", currentSession.target.name,
                    "votes", currentVotes, "required", required);
            for (Player p : players) {
                p.sendMessage(msg);
            }
        });

        checkPass();
    }

    public synchronized void cancelByAdmin(Player player) {
        if (currentSession == null) {
            player.sendMessage(Tr.t(player, "votekick.no_vote_in_progress"));
            return;
        }

        if (!player.admin) {
            return;
        }

        String adminName = player.name;
        broadcastToEligible(currentSession.target, (locale, players) -> {
            String msg = Tr.t(locale, "votekick.vote_canceled_admin", "name", adminName);
            for (Player p : players) {
                p.sendMessage(msg);
            }
        });

        reset();
    }

    private boolean checkPass() {
        if (currentSession == null) {
            return false;
        }
        if (getVotes() >= getVotesRequired()) {
            Player target = currentSession.target;
            int durationMinutes = KICK_DURATION_SECONDS / 60;
            long durationMillis = KICK_DURATION_SECONDS * 1000L;

            Utils.forEachPlayerLocale((locale, players) -> {
                String msg = Tr.t(locale, "votekick.vote_passed",
                        "target", target.name, "duration", durationMinutes);
                for (Player p : players) {
                    p.sendMessage(msg);
                }
            });

            if (target.con != null) {
                Vars.netServer.admins.handleKicked(target.uuid(), target.con.address, durationMillis);
            }

            Groups.player.each(p -> p.uuid().equals(target.uuid()), p -> p.kick(KickReason.vote, durationMillis));
            List<String> participants = new ArrayList<>();

            for (String key : currentSession.voted.keySet()) {
                var player = Vars.netServer.admins.getInfoOptional(key);
                if (player != null) {
                    participants.add(player.lastName);
                }
            }

            Log.info("Vote kick pass, participants: @", participants);

            apiGateway.fire(new ServerEvents.PlayerVoteKickEvent(Control.SERVER_ID, target.ip(), target.uuid(),
                    target.name, participants, currentSession.reason));

            reset();
            return true;
        }
        return false;
    }

    public synchronized void reset() {
        if (timeoutTask != null) {
            timeoutTask.cancel(true);
            timeoutTask = null;
        }
        currentSession = null;
    }

    @Listener
    public synchronized void onSessionRemoved(SessionRemovedEvent event) {
        if (currentSession != null && event.session.player == currentSession.target) {
            String targetName = currentSession.target.name;
            broadcastToEligible(currentSession.target, (locale, players) -> {
                String msg = Tr.t(locale, "votekick.target_left", "target", targetName);
                for (Player p : players) {
                    p.sendMessage(msg);
                }
            });
            reset();
        } else if (currentSession != null) {
            checkPass();
        }
    }

    @Destroy
    public synchronized void destroy() {
        reset();
        cooldowns.clear();
    }

    public void broadcastToEligible(Player target, BiConsumer<Locale, List<Player>> cons) {
        HashMap<Locale, List<Player>> groupByLocale = new HashMap<>();
        for (Player p : Groups.player) {
            if (!Vars.state.rules.pvp || p.team() == target.team()) {
                groupByLocale.computeIfAbsent(Utils.parseLocale(p.locale()), k -> new ArrayList<>()).add(p);
            }
        }
        groupByLocale.forEach(cons);
    }
}
