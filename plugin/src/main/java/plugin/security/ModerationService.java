package plugin.security;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import arc.util.Nullable;
import mindustry.game.EventType.PlayerBanEvent;
import mindustry.game.EventType.PlayerIpBanEvent;
import mindustry.gen.Player;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.Listener;
import plugin.event.PlayerKickEvent;
import plugin.event.UserBannedEvent;

@Component
public class ModerationService {

    private static final long TTL_MILLIS = 10_000L;

    private final Map<String, Long> recentBans = new ConcurrentHashMap<>();
    private final Map<String, Long> recentKicks = new ConcurrentHashMap<>();

    public void markBanned(@Nullable String identifier) {
        if (identifier != null && !identifier.isBlank()) {
            recentBans.put(identifier, System.currentTimeMillis());
        }
    }

    public boolean isBannedRecently(@Nullable String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        Long timestamp = recentBans.get(identifier);
        if (timestamp == null) {
            return false;
        }
        if (System.currentTimeMillis() - timestamp > TTL_MILLIS) {
            recentBans.remove(identifier);
            return false;
        }
        return true;
    }

    public void markKicked(@Nullable String identifier) {
        if (identifier != null && !identifier.isBlank()) {
            recentKicks.put(identifier, System.currentTimeMillis());
        }
    }

    public boolean isKickedRecently(@Nullable String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        Long timestamp = recentKicks.get(identifier);
        if (timestamp == null) {
            return false;
        }
        if (System.currentTimeMillis() - timestamp > TTL_MILLIS) {
            recentKicks.remove(identifier);
            return false;
        }
        return true;
    }

    public void kick(Player player, String reason, String actor) {
        Objects.requireNonNull(player, "player cannot be null");
        String cleanReason = (reason == null || reason.isBlank()) ? "No reason provided" : reason.trim();
        String cleanActor = (actor == null || actor.isBlank()) ? "SERVER" : actor.trim();

        String uuid = player.uuid();
        String ip = player.ip();
        markKicked(uuid);
        markKicked(ip);

        PluginEvents.fire(new PlayerKickEvent(player, uuid, ip, player.name, cleanReason, cleanActor));
        player.kick(cleanReason);
    }

    public boolean handlePlayerLeaveKicked(Player player) {
        if (player == null) {
            return false;
        }
        String uuid = player.uuid();
        String ip = player.ip();

        if (isBannedRecently(uuid) || isBannedRecently(ip)) {
            return false;
        }
        if (isKickedRecently(uuid) || isKickedRecently(ip)) {
            return false;
        }

        markKicked(uuid);
        markKicked(ip);
        PluginEvents.fire(new PlayerKickEvent(player, uuid, ip, player.name, "Disconnected", "SERVER"));
        return true;
    }

    @Listener
    private void onUserBanned(UserBannedEvent event) {
        markBanned(event.userId);
    }

    @Listener
    private void onPlayerBan(PlayerBanEvent event) {
        markBanned(event.uuid);
        if (event.player != null) {
            markBanned(event.player.ip());
            markBanned(event.player.uuid());
        }
    }

    @Listener
    private void onPlayerIpBan(PlayerIpBanEvent event) {
        markBanned(event.ip);
    }
}
