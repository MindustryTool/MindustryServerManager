package plugin.security;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import arc.util.Log;
import plugin.PluginEvents;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.database.Database;
import plugin.database.schema.BannedUsers;
import plugin.event.UserBannedEvent;

@Component
public class UserBanService {

    private final Database database;
    private final ModerationLogService moderationLogService;
    private final ModerationService moderationService;
    private final Set<String> bannedUserIds = ConcurrentHashMap.newKeySet();

    public UserBanService(Database database, ModerationLogService moderationLogService, ModerationService moderationService) {
        this.database = database;
        this.moderationLogService = moderationLogService;
        this.moderationService = moderationService;
    }

    @Init
    public void init() {
        createTableIfNotExists();
        loadFromDatabase();
    }

    public void createTableIfNotExists() {
        try {
            database.db().createTableIfNotExists(BannedUsers.TABLE);
        } catch (Exception e) {
            Log.err("Failed to create banned_users table", e);
        }
    }

    public synchronized void loadFromDatabase() {
        bannedUserIds.clear();
        try {
            var rows = database.db().select(BannedUsers.USER_ID)
                    .from(BannedUsers.TABLE)
                    .fetch();
            for (var row : rows) {
                String id = row.get(BannedUsers.USER_ID);
                if (id != null) {
                    bannedUserIds.add(id);
                }
            }
        } catch (Exception e) {
            Log.err("Failed to load banned users from database", e);
        }
    }

    public boolean isBanned(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        return bannedUserIds.contains(userId.trim());
    }

    public synchronized boolean ban(String userId, String reason, String actor) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Ban reason must not be empty");
        }

        String cleanId = userId.trim();
        String cleanReason = reason.trim();
        String cleanActor = (actor == null || actor.trim().isEmpty()) ? "SERVER" : actor.trim();

        boolean added = bannedUserIds.add(cleanId);
        if (added) {
            long now = System.currentTimeMillis();
            try {
                database.db().insert(BannedUsers.TABLE)
                        .set(BannedUsers.USER_ID, cleanId)
                        .set(BannedUsers.REASON, cleanReason)
                        .set(BannedUsers.ACTOR, cleanActor)
                        .set(BannedUsers.CREATED_AT, now)
                        .onConflictDoUpdate(BannedUsers.USER_ID, BannedUsers.REASON, BannedUsers.ACTOR, BannedUsers.CREATED_AT)
                        .execute();
            } catch (Exception e) {
                Log.err("Failed to insert banned user into database", e);
            }

            if (moderationLogService != null) {
                moderationLogService.logBan("BAN", cleanId, null, null, null, cleanReason, cleanActor);
            }

            if (moderationService != null) {
                moderationService.markBanned(cleanId);
            }

            PluginEvents.fire(new UserBannedEvent(cleanId, cleanReason, cleanActor));
        }
        return added;
    }

    public boolean ban(String userId, String reason) {
        return ban(userId, reason, "SERVER");
    }

    public synchronized boolean unban(String userId, String actor) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }

        String cleanId = userId.trim();
        String cleanActor = (actor == null || actor.trim().isEmpty()) ? "SERVER" : actor.trim();

        boolean removed = bannedUserIds.remove(cleanId);
        if (removed) {
            try {
                database.db().delete(BannedUsers.TABLE)
                        .where(BannedUsers.USER_ID.eq(cleanId))
                        .execute();
            } catch (Exception e) {
                Log.err("Failed to delete banned user from database", e);
            }

            if (moderationLogService != null) {
                moderationLogService.logBan("UNBAN", cleanId, null, null, null, "Unbanned", cleanActor);
            }
        }
        return removed;
    }

    public boolean unban(String userId) {
        return unban(userId, "SERVER");
    }

    public Set<String> getBannedUserIds() {
        return Collections.unmodifiableSet(bannedUserIds);
    }
}
