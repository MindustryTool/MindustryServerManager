package plugin.repository;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.util.Log;
import mindustry.gen.Player;
import plugin.database.DB;
import plugin.event.PluginUnloadEvent;
import plugin.event.SessionRemovedEvent;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.type.SessionData;
import plugin.utils.JsonUtils;

public class SessionRepository {
    private static final ConcurrentHashMap<String, SessionData> cache = new ConcurrentHashMap<>();

    private static final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public static void init() {
        createTableIfNotExists();

        ServerControl.BACKGROUND_SCHEDULER
                .scheduleWithFixedDelay(SessionRepository::flushBatch, 10, 30, TimeUnit.SECONDS);

        PluginEvents.run(PluginUnloadEvent.class, SessionRepository::unload);
        PluginEvents.run(SessionRemovedEvent.class, SessionRepository::flushBatch);
    }

    private static void unload() {
        try {
            flushBatch();
        } catch (Exception e) {
            Log.err("Failed to flush session repository on unload: @", e.getMessage());
        } finally {
            cache.clear();
            dirty.clear();
        }
    }

    public static SessionData get(Player player) {
        return get(player.uuid());
    }

    public static SessionData get(String uuid) {
        var existing = cache.get(uuid);

        if (existing != null) {
            return existing;
        }

        try {
            var loaded = read(uuid);
            cache.put(uuid, loaded);
            return loaded;
        } catch (Exception e) {
            Log.err("Error while loading session data for uuid: @", uuid, e);
            return new SessionData();
        }
    }

    public static void put(String uuid, SessionData data) {
        cache.put(uuid, data);
        dirty.add(uuid);
    }

    public static void markDirty(String uuid) {
        if (cache.get(uuid) != null) {
            dirty.add(uuid);
        }
    }

    public static void remove(String uuid) {
        cache.remove(uuid);
        dirty.remove(uuid);
    }

    public static void flushBatch() {
        if (dirty.isEmpty()) {
            return;
        }

        for (var uuid : dirty.toArray(new String[0])) {
            var data = cache.get(uuid);
            if (data != null) {
                write(uuid, data);
            }
            dirty.remove(uuid);
        }
    }

    private static SessionData read(String uuid) throws SQLException {
        var sql = "SELECT data FROM sessions WHERE uuid = ?";
        try (var conn = DB.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);

            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    var json = rs.getString(1);

                    if (json == null || json.isEmpty()) {
                        throw new IllegalArgumentException("No session data found for uuid: " + uuid);
                    }
                    return JsonUtils.readJsonAsClass(json, SessionData.class);
                } else {
                    return new SessionData();
                }
            }
        }
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
