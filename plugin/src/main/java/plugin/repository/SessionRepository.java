package plugin.repository;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;

import arc.util.Log;
import plugin.database.DB;
import plugin.event.PluginUnloadEvent;
import plugin.event.SessionRemovedEvent;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.type.SessionData;
import plugin.utils.JsonUtils;

public class SessionRepository {
    private static final Cache<String, SessionData> cache = Caffeine.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .evictionListener((key, value, cause) -> {
                if (cause == RemovalCause.EXPIRED || cause == RemovalCause.EXPLICIT) {
                    write((String) key, (SessionData) value);
                }
            })
            .build();

    private static final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public static void init() {
        createTableIfNotExists();

        ServerControl.BACKGROUND_SCHEDULER
                .scheduleWithFixedDelay(SessionRepository::flushBatch, 10, 10, TimeUnit.SECONDS);

        PluginEvents.run(PluginUnloadEvent.class, SessionRepository::unload);
        PluginEvents.on(SessionRemovedEvent.class, event -> write(event.session.player.uuid(), event.session.data));
    }

    private static void unload() {
        try {
            flushBatch();
        } catch (Exception e) {
            Log.err("Failed to flush session repository on unload: @", e.getMessage());
        } finally {
            cache.invalidateAll();
            dirty.clear();
        }
    }

    public static SessionData get(String uuid) {
        var existing = cache.getIfPresent(uuid);
        if (existing != null) {
            return existing;
        }
        var loaded = read(uuid);
        cache.put(uuid, loaded);
        return loaded;
    }

    public static void put(String uuid, SessionData data) {
        cache.put(uuid, data);
        dirty.add(uuid);
    }

    public static void markDirty(String uuid) {
        if (cache.getIfPresent(uuid) != null) {
            dirty.add(uuid);
        }
    }

    public static void remove(String uuid) {
        cache.invalidate(uuid);
        dirty.remove(uuid);
    }

    public static void flushBatch() {
        if (dirty.isEmpty()) {
            return;
        }

        for (var uuid : dirty.toArray(new String[0])) {
            var data = cache.getIfPresent(uuid);
            if (data != null) {
                write(uuid, data);
            }
            dirty.remove(uuid);
        }
    }

    private static SessionData read(String uuid) {
        SessionData pdata = new SessionData();
        try {
            var sql = "SELECT data FROM sessions WHERE uuid = ?";
            try (var conn = DB.getConnection(); var ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        var json = rs.getString(1);
                        if (json != null && !json.isEmpty()) {
                            pdata = JsonUtils.readJsonAsClass(json, SessionData.class);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.err("Error while loading session", e);
        }
        return pdata;
    }

    private static void write(String uuid, SessionData pdata) {
        try {
            var sql = "INSERT INTO sessions(uuid, data) VALUES(?, ?) ON CONFLICT(uuid) DO UPDATE SET data = excluded.data";
            try (var conn = DB.getConnection(); var ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.setString(2, JsonUtils.toJsonString(pdata));
                ps.executeUpdate();
            }
        } catch (Exception e) {
            Log.err("Error while saving session", e);
        }
    }

    private static void createTableIfNotExists() {
        try {
            var sql = "CREATE TABLE IF NOT EXISTS sessions (uuid TEXT PRIMARY KEY, data TEXT NOT NULL)";
            try (var conn = DB.getConnection(); var stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
            }
        } catch (Exception e) {
            Log.err("Failed to create sessions table: @", e.getMessage());
        }
    }
}
