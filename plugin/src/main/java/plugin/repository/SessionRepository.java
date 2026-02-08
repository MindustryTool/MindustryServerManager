package plugin.repository;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.struct.Seq;
import arc.util.Log;
import lombok.AllArgsConstructor;
import mindustry.gen.Player;
import plugin.database.DB;
import plugin.event.SessionRemovedEvent;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.Control;
import plugin.type.SessionData;
import plugin.utils.ExpUtils;
import plugin.utils.JsonUtils;

@Component
public class SessionRepository {
    private final ConcurrentHashMap<String, SessionData> cache = new ConcurrentHashMap<>();

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    @Init
    public void init() {
        createTableIfNotExists();
        Control.SCHEDULER.scheduleWithFixedDelay(this::flushBatch, 10, 30, TimeUnit.SECONDS);
    }

    @Listener
    public void onSessionRemoved(SessionRemovedEvent event) {
        write(event.getSession().player.uuid(), event.getSession().getData());
        cache.remove(event.getSession().player.uuid());
    }

    @Destroy
    public void destroy() {
        try {
            for (var entry : cache.entrySet()) {
                write(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            Log.err("Failed to flush session repository on unload: @", e.getMessage());
        } finally {
            cache.clear();
            dirty.clear();
        }
    }

    public SessionData get(Player player) {
        return get(player.uuid());
    }

    public SessionData get(String uuid) {
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

    public void put(String uuid, SessionData data) {
        cache.put(uuid, data);
        dirty.add(uuid);
    }

    public void markDirty(String uuid) {
        if (cache.get(uuid) != null) {
            dirty.add(uuid);
        }
    }

    public void remove(String uuid) {
        cache.remove(uuid);
        dirty.remove(uuid);
    }

    public void flushBatch() {
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

    @AllArgsConstructor
    public static class RankData {
        public String uuid;
        public SessionData data;
    }

    public Seq<RankData> getLeaderBoard(int size) {
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

    private SessionData read(String uuid) throws SQLException {
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

    private void write(String uuid, SessionData pdata) {
        try {
            var sql = "INSERT INTO sessions(uuid, data, totalExp) VALUES(?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET data = excluded.data, totalExp = excluded.totalExp";

            DB.prepare(sql, statement -> {
                var now = Instant.now().toEpochMilli();
                var playTime = now - pdata.lastSaved;
                pdata.playTime += playTime;
                pdata.lastSaved = now;
                statement.setString(1, uuid);
                statement.setString(2, JsonUtils.toJsonString(pdata));
                statement.setLong(3, ExpUtils.getTotalExp(pdata, playTime));
                statement.executeUpdate();
            });
        } catch (Exception e) {
            Log.err("Error while saving session", e);
        }
    }

    private void createTableIfNotExists() {
        try {
            var sql = "CREATE TABLE IF NOT EXISTS sessions (uuid TEXT PRIMARY KEY, data TEXT NOT NULL, totalExp INTEGER DEFAULT 0)";

            DB.statement(statement -> {
                statement.executeUpdate(sql);

                if (!DB.hasColumn(statement, "sessions", "totalExp")) {
                    statement.executeUpdate("ALTER TABLE sessions ADD COLUMN totalExp INTEGER DEFAULT 0");
                }
            });

        } catch (Exception e) {
            Log.err("Failed to create sessions table: @", e);
        }
    }
}
