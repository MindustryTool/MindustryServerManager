package plugin.security;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import arc.Core;
import arc.util.Log;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.core.Registry;
import plugin.session.Session;
import plugin.session.SessionService;
import plugin.utils.JsonUtils;
import plugin.utils.Tr;

@Component
public class UserBanService {
    public static final String SETTING_KEY = "banned_user_ids";

    private final Set<String> bannedUserIds = ConcurrentHashMap.newKeySet();

    @Init
    public void init() {
        loadFromSettings();
    }

    public synchronized void loadFromSettings() {
        bannedUserIds.clear();
        String json = Core.settings.getString(SETTING_KEY, "[]");
        try {
            List<String> list = JsonUtils.readJsonAsArrayClass(json, String.class);
            if (list != null) {
                bannedUserIds.addAll(list);
            }
        } catch (Exception e) {
            Log.err("Failed to load banned user IDs from settings", e);
        }
    }

    public synchronized void saveToSettings() {
        try {
            String json = JsonUtils.toJsonString(bannedUserIds);
            Core.settings.put(SETTING_KEY, json);
            Core.settings.forceSave();
        } catch (Exception e) {
            Log.err("Failed to save banned user IDs to settings", e);
        }
    }

    public boolean isBanned(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        return bannedUserIds.contains(userId.trim());
    }

    public synchronized boolean ban(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        String cleanId = userId.trim();
        boolean added = bannedUserIds.add(cleanId);
        if (added) {
            saveToSettings();
            kickOnlinePlayerWithUserId(cleanId);
        }
        return added;
    }

    public synchronized boolean unban(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        String cleanId = userId.trim();
        boolean removed = bannedUserIds.remove(cleanId);
        if (removed) {
            saveToSettings();
        }
        return removed;
    }

    public Set<String> getBannedUserIds() {
        return Collections.unmodifiableSet(bannedUserIds);
    }

    public void kickOnlinePlayerWithUserId(String userId) {
        SessionService sessionService = Registry.getOrNull(SessionService.class);
        if (sessionService == null || Groups.player == null) {
            return;
        }

        for (Player p : Groups.player) {
            var sessionOpt = sessionService.get(p);
            if (sessionOpt.isPresent()) {
                Session session = sessionOpt.get();
                if (session.login != null && userId.equals(session.login.getUserId())) {
                    p.kick(Tr.t(session, "security.account_banned"));
                }
            }
        }
    }
}
