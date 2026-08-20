package plugin.session;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.struct.Seq;
import arc.util.Log;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import mindustry.gen.Player;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.database.Database;
import plugin.database.schema.Sessions;
import plugin.utils.JsonUtils;

@Component
@RequiredArgsConstructor
public class SessionRepository {
    private final Database database;
    private final ConcurrentHashMap<String, SessionData> cache = new ConcurrentHashMap<>();

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    @Init
    public void init() {
        Tasks.io("session exp migration", () -> {
            createTableIfNotExists();
            migrateStoredExpCounters();
        });
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
        var rows = database.db().select(Sessions.UUID, Sessions.DATA, Sessions.TOTAL_EXP)
                .from(Sessions.TABLE)
                .orderBy(Sessions.TOTAL_EXP.desc(), Sessions.UUID.asc())
                .limit(size)
                .fetch();

        Seq<RankData> players = new Seq<>();

        for (var row : rows) {
            var uuid = row.get(Sessions.UUID);
            var json = row.get(Sessions.DATA);
            var totalExp = row.get(Sessions.TOTAL_EXP);

            if (json == null || json.isEmpty()) {
                throw new IllegalArgumentException("No session data found for uuid: " + uuid);
            }
            var data = JsonUtils.readJsonAsClass(json, SessionData.class);

            players.add(new RankData(uuid, data, totalExp));
        }

        return players;
    }

    public int getRank(String uuid) {
        // Raw hook: the correlated subquery (count of sessions with higher exp, uuid
        // tiebreak)
        // is beyond the typed ORM API. Output column aliased for position-independent
        // reads.
        var sql = """
                    SELECT CASE WHEN p.uuid IS NULL THEN -1 ELSE
                        1 + (SELECT COUNT(*) FROM sessions s
                             WHERE s.totalExp > p.totalExp
                                OR (s.totalExp = p.totalExp AND s.uuid < p.uuid))
                    END AS rank
                    FROM (SELECT uuid, totalExp FROM sessions WHERE uuid = ?) p
                """;

        var rows = database.db().rawQuery(sql, uuid);

        if (rows.isEmpty()) {
            return -1;
        }

        return rows.get(0).getInt("rank");
    }

    private SessionData read(String uuid) {
        var row = database.db().select(Sessions.DATA).from(Sessions.TABLE)
                .where(Sessions.UUID.eq(uuid))
                .fetchOne()
                .orElse(null);

        if (row == null) {
            return null;
        }

        var json = row.get(Sessions.DATA);

        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("Session data is empty for uuid: " + uuid);
        }
        return JsonUtils.readJsonAsClass(json, SessionData.class);
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

            database.db().insert(Sessions.TABLE)
                    .set(Sessions.UUID, uuid)
                    .set(Sessions.DATA, json)
                    .set(Sessions.TOTAL_EXP, totalExp)
                    .onConflictDoUpdate(Sessions.UUID, Sessions.DATA, Sessions.TOTAL_EXP)
                    .execute();
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

    private int seedStoredExpCounters() {
        var rows = database.db().select(Sessions.UUID, Sessions.DATA).from(Sessions.TABLE).fetch();

        int repaired = 0;

        for (var row : rows) {
            var uuid = row.get(Sessions.UUID);
            var json = row.get(Sessions.DATA);

            if (json == null || json.isEmpty()) {
                continue;
            }

            var data = JsonUtils.readJsonAsClass(json, SessionData.class);

            if (data.exp > 0 || data.playTime <= 0) {
                continue;
            }

            data.exp = data.playTime / 1000;

            var updatedJson = JsonUtils.toJsonString(data);
            long totalExp = (long) data.exp;

            database.db().update(Sessions.TABLE)
                    .set(Sessions.DATA, updatedJson)
                    .set(Sessions.TOTAL_EXP, totalExp)
                    .where(Sessions.UUID.eq(uuid))
                    .execute();

            repaired++;
        }

        return repaired;
    }

    private void createTableIfNotExists() {
        try {
            database.db().createTableIfNotExists(Sessions.TABLE);

            // Legacy databases created before the stored exp counter may be missing the
            // column.
            database.db().addColumnIfMissing(Sessions.TABLE, Sessions.TOTAL_EXP);
        } catch (Exception e) {
            Log.err("Failed to create sessions table: @", e);
        }
    }
}
