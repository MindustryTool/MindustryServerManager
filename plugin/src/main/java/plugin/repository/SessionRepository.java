package plugin.repository;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import arc.struct.Seq;
import arc.util.Log;
import lombok.AllArgsConstructor;
import mindustry.gen.Player;
import plugin.database.DB;
import plugin.event.PluginUnloadEvent;
import plugin.event.SessionRemovedEvent;
import plugin.PluginEvents;
import plugin.ServerControl;
import plugin.type.SessionData;
import plugin.utils.ExpUtils;
import plugin.utils.JsonUtils;

public class SessionRepository {
    private static final Cache<String, SessionData> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(1))
            .build();

    private static final Set<String> dirty = ConcurrentHashMap.newKeySet();

    public static void init() {
        createTableIfNotExists();

        ServerControl.BACKGROUND_SCHEDULER
                .scheduleWithFixedDelay(SessionRepository::flushBatch, 10, 30, TimeUnit.SECONDS);

        PluginEvents.run(PluginUnloadEvent.class, SessionRepository::unload);
        PluginEvents.run(SessionRemovedEvent.class, SessionRepository::flushBatch);

        ServerControl.backgroundTask("update rank", () -> {
            var players = getLeaderBoard(100);
            for (var player : players) {
                player.data.joinedAt = Instant.now().toEpochMilli();
                write(player.uuid, player.data);
                Log.info("Update rank for player: @", player.data.name);
            }
        });
    }

    private static void unload() {
        try {
            for (var entry : cache.asMap().entrySet()) {
                write(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            Log.err("Failed to flush session repository on unload: @", e.getMessage());
        } finally {
            cache.invalidateAll();
            dirty.clear();
        }
    }

    public static SessionData get(Player player) {
        return get(player.uuid());
    }

    public static SessionData get(String uuid) {
        var existing = cache.getIfPresent(uuid);

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

    @AllArgsConstructor
    public static class RankData {
        public String uuid;
        public SessionData data;
    }

    public static Seq<RankData> getLeaderBoard(int size) {
        var sql = "SELECT uuid, data FROM sessions ORDER BY totalExp DESC LIMIT ?";

        return DB.prepare(sql, statement -> {
            statement.setInt(1, size);

            try (var rs = statement.executeQuery()) {
                Seq<RankData> players = new Seq<>();

                while (rs.next()) {
                    var uuid = rs.getString(1);
                    var json = rs.getString(2);

                    if (json == null || json.isEmpty()) {
                        throw new IllegalArgumentException("No session data found for uuid: " + uuid);
                    }
                    var data = JsonUtils.readJsonAsClass(json, SessionData.class);

                    players.add(new RankData(uuid, data));
                }

                return players;
            }
        });
    }

    private static SessionData read(String uuid) throws SQLException {
        var sql = "SELECT data FROM sessions WHERE uuid = ?";

        return DB.prepare(sql, ps -> {
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
        });
    }

    private static void write(String uuid, SessionData pdata) {
        try {
            var sql = "INSERT INTO sessions(uuid, data, totalExp) VALUES(?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET data = excluded.data, totalExp = excluded.totalExp";

            DB.prepare(sql, statement -> {
                statement.setString(1, uuid);
                statement.setString(2, JsonUtils.toJsonString(pdata));
                statement.setLong(3, ExpUtils.getTotalExp(pdata, Instant.now().toEpochMilli() - pdata.joinedAt));
                statement.executeUpdate();
            });
        } catch (Exception e) {
            Log.err("Error while saving session", e);
        }
    }

    private static void createTableIfNotExists() {
        try {
            var sql = "CREATE TABLE IF NOT EXISTS sessions (uuid TEXT PRIMARY KEY, data TEXT NOT NULL, totalExp INTEGER DEFAULT 0)";

            DB.statement(statement -> {
                statement.executeUpdate(sql);

                if (!DB.hasColumn(statement, "sessions", "totalExp")) {
                    statement.executeUpdate("ALTER TABLE sessions ADD COLUMN totalExp INTEGER DEFAULT 0");
                }
            });

        } catch (Exception e) {
            Log.err("Failed to create sessions table: @", e.getMessage());
        }
    }
}
