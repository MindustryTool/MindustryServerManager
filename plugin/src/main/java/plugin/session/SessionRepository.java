package plugin.session;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.struct.Seq;
import arc.util.Log;
import lombok.AllArgsConstructor;
import mindustry.gen.Player;
import plugin.database.DB;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.utils.JsonUtils;

@Component
public class SessionRepository {
    private final ConcurrentHashMap<String, SessionData> cache = new ConcurrentHashMap<>();

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    @Init
    public void init() {
        createTableIfNotExists();
        migrateStoredExpCounters();
    }

    @Listener
    public void onSessionRemoved(SessionRemovedEvent event) {
        String uuid = event.getSession().player.uuid();
        write(uuid, event.getSession().getData());
        cache.remove(uuid);
        dirty.remove(uuid);
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

        SessionData loaded;
        try {
            loaded = read(uuid);
        } catch (Exception e) {
            Log.err("Error while loading session data for uuid: @", uuid, e);
            var cached = cache.get(uuid);

            if (cached != null) {
                return cached;
            }

            loaded = new SessionData();
        }

        if (loaded == null) {
            loaded = new SessionData();
        }

        cache.put(uuid, loaded);
        return loaded;
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

    public void markDirty(Player player) {
        markDirty(player.uuid());
    }

    public void markDirty(Session session) {
        markDirty(session.player);
    }

    public void remove(String uuid) {
        cache.remove(uuid);
        dirty.remove(uuid);
    }

    @Schedule(delay = 10, fixedDelay = 10, unit = TimeUnit.SECONDS)
    public void flushBatch() {
        if (dirty.isEmpty()) {
            return;
        }

        for (var uuid : dirty.toArray(new String[0])) {
            if (!dirty.remove(uuid)) {
                continue;
            }

            var data = cache.get(uuid);
            if (data != null) {
                write(uuid, data);
            }
        }
    }

    @AllArgsConstructor
    public static class RankData {
        public String uuid;
        public SessionData data;
        public long totalExp;
    }

    public Seq<RankData> leaderBoard(int size) {
        var sql = "SELECT uuid, data, totalExp FROM sessions ORDER BY totalExp DESC, uuid ASC LIMIT ?";

        return DB.prepare(sql, statement -> {
            statement.setInt(1, size);

            try (var rs = statement.executeQuery()) {
                Seq<RankData> players = new Seq<>();

                while (rs.next()) {
                    var uuid = rs.getString(1);
                    var json = rs.getString(2);
                    var totalExp = rs.getLong(3);

                    if (json == null || json.isEmpty()) {
                        throw new IllegalArgumentException("No session data found for uuid: " + uuid);
                    }
                    var data = JsonUtils.readJsonAsClass(json, SessionData.class);

                    players.add(new RankData(uuid, data, totalExp));
                }

                return players;
            }
        });
    }

    public int getRank(String uuid) {
        var sql = """
                    SELECT CASE WHEN p.uuid IS NULL THEN -1 ELSE
                        1 + (SELECT COUNT(*) FROM sessions s
                             WHERE s.totalExp > p.totalExp
                                OR (s.totalExp = p.totalExp AND s.uuid < p.uuid))
                    END
                    FROM (SELECT uuid, totalExp FROM sessions WHERE uuid = ?) p
                """;

        return DB.prepare(sql, ps -> {
            ps.setString(1, uuid);

            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return -1;
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
                        throw new IllegalArgumentException("Session data is empty for uuid: " + uuid);
                    }
                    return JsonUtils.readJsonAsClass(json, SessionData.class);
                } else {
                    return null;
                }
            }
        });
    }

    private void write(String uuid, SessionData pdata) {
        try {
            String json;
            long totalExp;

            synchronized (pdata) {
                var now = Instant.now().toEpochMilli();
                var playTime = Math.max(0, now - pdata.lastSaved);
                pdata.playTime += playTime;
                pdata.lastSaved = now;

                json = JsonUtils.toJsonString(pdata);
                totalExp = (long) pdata.exp;
            }

            var sql = "INSERT INTO sessions(uuid, data, totalExp) VALUES(?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET data = excluded.data, totalExp = excluded.totalExp";

            DB.prepare(sql, statement -> {
                statement.setString(1, uuid);
                statement.setString(2, json);
                statement.setLong(3, totalExp);
                statement.executeUpdate();
            });
        } catch (Exception e) {
            Log.err("Error while saving session", e);
        }
    }

    private void migrateStoredExpCounters() {
        if (Core.settings.has("EXP_RECALCULATED_5")) {
            return;
        }

        try {
            int repaired = seedStoredExpCounters();
            Core.settings.put("EXP_RECALCULATED_5", true);
            Core.settings.forceSave();
            Log.info("Exp migration complete (v5): @ sessions repaired", repaired);
        } catch (Exception e) {
            Log.err("Failed to migrate exp to stored counter (v5): @", e.getMessage());
        }
    }

    private int seedStoredExpCounters() throws SQLException {
        var rows = DB.statement(statement -> {
            var list = new ArrayList<String[]>();
            try (var rs = statement.executeQuery("SELECT uuid, data FROM sessions")) {
                while (rs.next()) {
                    list.add(new String[] { rs.getString("uuid"), rs.getString("data") });
                }
            }
            return list;
        });

        var updateSql = "UPDATE sessions SET data = ?, totalExp = ? WHERE uuid = ?";
        int repaired = 0;

        for (var row : rows) {
            var uuid = row[0];
            var json = row[1];

            if (json == null || json.isEmpty()) {
                continue;
            }

            var data = JsonUtils.readJsonAsClass(json, SessionData.class);

            if (data.exp > 0 || data.playTime <= 0) {
                continue;
            }

            data.exp = ExpUtils.playTimeToExp(data.playTime);

            var updatedJson = JsonUtils.toJsonString(data);
            long totalExp = (long) data.exp;

            DB.prepare(updateSql, ps -> {
                ps.setString(1, updatedJson);
                ps.setLong(2, totalExp);
                ps.setString(3, uuid);
                ps.executeUpdate();
            });

            repaired++;
        }

        return repaired;
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
