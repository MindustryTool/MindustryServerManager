package plugin.maprecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.database.Database;
import plugin.database.schema.MapRecords;
import plugin.orm.Row;
import plugin.utils.JsonUtils;

@Component
@RequiredArgsConstructor
public class MapRecordRepository {
    private final Database database;

    @Init
    public void init() {
        database.db().createTableIfNotExists(
                MapRecords.TABLE,
                MapRecords.ID,
                MapRecords.MAP_NAME,
                MapRecords.GAMEMODE,
                MapRecords.DURATION_MS,
                MapRecords.PLAYERS,
                MapRecords.CREATED_AT
        );
    }

    public boolean isAscending(String gamemode) {
        if (gamemode == null) {
            return true;
        }
        return !gamemode.equalsIgnoreCase("survival") && !gamemode.equalsIgnoreCase("TowerDefense");
    }

    public List<MapRecord> getTopRecords(String mapName, String gamemode, int limit) {
        var query = database.db().select(
                        MapRecords.ID,
                        MapRecords.MAP_NAME,
                        MapRecords.GAMEMODE,
                        MapRecords.DURATION_MS,
                        MapRecords.PLAYERS,
                        MapRecords.CREATED_AT)
                .from(MapRecords.TABLE)
                .where(MapRecords.MAP_NAME.eq(mapName).and(MapRecords.GAMEMODE.eq(gamemode)));

        if (isAscending(gamemode)) {
            query.orderBy(MapRecords.DURATION_MS.asc(), MapRecords.CREATED_AT.asc());
        } else {
            query.orderBy(MapRecords.DURATION_MS.desc(), MapRecords.CREATED_AT.asc());
        }

        if (limit > 0) {
            query.limit(limit);
        }

        var rows = query.fetch();
        List<MapRecord> records = new ArrayList<>(rows.size());
        for (var row : rows) {
            records.add(toRecord(row));
        }
        return records;
    }

    public Optional<MapRecord> getBestRecord(String mapName, String gamemode) {
        List<MapRecord> top = getTopRecords(mapName, gamemode, 1);
        return top.isEmpty() ? Optional.empty() : Optional.of(top.get(0));
    }

    public synchronized Optional<RecordSaveResult> saveAttempt(
            String mapName,
            String gamemode,
            long durationMs,
            List<MapRecordPlayer> players,
            long createdAt) {
        List<MapRecord> current = getTopRecords(mapName, gamemode, 10);
        boolean qualifies = false;
        if (current.size() < 10) {
            qualifies = true;
        } else {
            MapRecord tenth = current.get(current.size() - 1);
            if (isAscending(gamemode)) {
                qualifies = durationMs < tenth.durationMs();
            } else {
                qualifies = durationMs > tenth.durationMs();
            }
        }

        if (!qualifies) {
            return Optional.empty();
        }

        String playersJson = JsonUtils.toJsonString(players);
        database.db().insert(MapRecords.TABLE)
                .set(MapRecords.MAP_NAME, mapName)
                .set(MapRecords.GAMEMODE, gamemode)
                .set(MapRecords.DURATION_MS, durationMs)
                .set(MapRecords.PLAYERS, playersJson)
                .set(MapRecords.CREATED_AT, createdAt)
                .execute();

        List<MapRecord> updated = getTopRecords(mapName, gamemode, 20);
        if (updated.size() > 10) {
            List<Long> toPrune = new ArrayList<>();
            for (int i = 10; i < updated.size(); i++) {
                toPrune.add(updated.get(i).id());
            }
            database.db().delete(MapRecords.TABLE)
                    .where(MapRecords.ID.in(toPrune))
                    .execute();
        }

        int rank = 1;
        for (int i = 0; i < Math.min(10, updated.size()); i++) {
            MapRecord rec = updated.get(i);
            if (rec.createdAt() == createdAt && rec.durationMs() == durationMs) {
                rank = i + 1;
                return Optional.of(new RecordSaveResult(rec, rank, rank == 1));
            }
        }

        for (int i = 0; i < Math.min(10, updated.size()); i++) {
            if (updated.get(i).durationMs() == durationMs) {
                rank = i + 1;
                break;
            }
        }
        return Optional.of(new RecordSaveResult(updated.get(rank - 1), rank, rank == 1));
    }

    private MapRecord toRecord(Row row) {
        long id = row.get(MapRecords.ID);
        String mapName = row.get(MapRecords.MAP_NAME);
        String gamemode = row.get(MapRecords.GAMEMODE);
        long durationMs = row.get(MapRecords.DURATION_MS);
        String playersJson = row.get(MapRecords.PLAYERS);
        long createdAt = row.get(MapRecords.CREATED_AT);

        List<MapRecordPlayer> players = JsonUtils.readJsonAsArrayClass(playersJson, MapRecordPlayer.class);
        return new MapRecord(id, mapName, gamemode, durationMs, players, createdAt);
    }
}
