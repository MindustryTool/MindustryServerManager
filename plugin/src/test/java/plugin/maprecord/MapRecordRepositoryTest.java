package plugin.maprecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import arc.Core;
import arc.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;

public class MapRecordRepositoryTest {

    @TempDir
    Path tempDir;

    private Database database;
    private MapRecordRepository repository;

    @BeforeEach
    void setUp() {
        if (Core.settings == null) {
            Core.settings = new Settings();
        }
        Core.settings.put(MapRecordRepository.MIGRATION_KEY, true);
        Database.setTestPath(tempDir.resolve("map_records.db"));
        database = new Database();
        repository = new MapRecordRepository(database);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
    }

    @Test
    void testAttackSortsAscendingAndLimitsToTen() {
        String map = "ancient-valley";
        String mode = "attack";
        List<MapRecordPlayer> players = List.of(new MapRecordPlayer("u1", "Player1"));

        // Insert 12 attempts with durations 120, 110, ..., 10
        for (int i = 12; i >= 1; i--) {
            long duration = i * 1000L;
            Optional<RecordSaveResult> result = repository.saveAttempt(map, mode, duration, players, 1000L + i);
            assertTrue(result.isPresent());
        }

        List<MapRecord> top = repository.getTopRecords(map, mode, 15);
        assertEquals(10, top.size(), "Repository should strictly prune beyond 10 records");

        // Should be ordered ascending: 1000, 2000, ..., 10000
        for (int i = 0; i < 10; i++) {
            assertEquals((i + 1) * 1000L, top.get(i).durationMs());
        }

        Optional<MapRecord> best = repository.getBestRecord(map, mode);
        assertTrue(best.isPresent());
        assertEquals(1000L, best.get().durationMs());

        // Attempt with worse duration (15000) should not qualify
        Optional<RecordSaveResult> worse = repository.saveAttempt(map, mode, 15000L, players, 9999L);
        assertFalse(worse.isPresent());
        assertEquals(10, repository.getTopRecords(map, mode, 15).size());
    }

    @Test
    void testSurvivalSortsDescendingAndPrunes() {
        String map = "desert-storm";
        String mode = "survival";
        List<MapRecordPlayer> players = List.of(new MapRecordPlayer("u2", "Surviver"));

        // Insert attempts with durations: 1000, 2000, ..., 12000
        for (int i = 1; i <= 12; i++) {
            long duration = i * 1000L;
            Optional<RecordSaveResult> result = repository.saveAttempt(map, mode, duration, players, 2000L + i);
            assertTrue(result.isPresent());
        }

        List<MapRecord> top = repository.getTopRecords(map, mode, 20);
        assertEquals(10, top.size());

        // Survival should be ordered descending: 12000, 11000, ..., 3000
        assertEquals(12000L, top.get(0).durationMs());
        assertEquals(3000L, top.get(9).durationMs());

        Optional<MapRecord> best = repository.getBestRecord(map, mode);
        assertTrue(best.isPresent());
        assertEquals(12000L, best.get().durationMs());

        // Attempt with worse duration (2000) should not qualify
        Optional<RecordSaveResult> worse = repository.saveAttempt(map, mode, 2000L, players, 9999L);
        assertFalse(worse.isPresent());
    }

    @Test
    void testParticipantsPreservedInJson() {
        String map = "island";
        String mode = "flood";
        List<MapRecordPlayer> players = List.of(
                new MapRecordPlayer("uuid-alpha", "Alice"),
                new MapRecordPlayer("uuid-beta", "Bob")
        );

        Optional<RecordSaveResult> res = repository.saveAttempt(map, mode, 5000L, players, 12345L);
        assertTrue(res.isPresent());
        assertTrue(res.get().isNewBest());
        assertEquals(1, res.get().rank());

        MapRecord rec = res.get().record();
        assertEquals(2, rec.players().size());
        assertEquals("uuid-alpha", rec.players().get(0).uuid());
        assertEquals("Alice", rec.players().get(0).name());
        assertEquals("uuid-beta", rec.players().get(1).uuid());
        assertEquals("Bob", rec.players().get(1).name());
    }

    @Test
    void testClearAll() {
        String map = "clear-map";
        String mode = "attack";
        List<MapRecordPlayer> players = List.of(new MapRecordPlayer("u1", "Player1"));

        repository.saveAttempt(map, mode, 5000L, players, 1000L);
        assertEquals(1, repository.getTopRecords(map, mode, 10).size());

        repository.clearAll();
        assertTrue(repository.getTopRecords(map, mode, 10).isEmpty());
    }

    @Test
    void testClearByMap() {
        String map1 = "map-one";
        String map2 = "map-two";
        String mode = "attack";
        List<MapRecordPlayer> players = List.of(new MapRecordPlayer("u1", "Player1"));

        repository.saveAttempt(map1, mode, 5000L, players, 1000L);
        repository.saveAttempt(map2, mode, 6000L, players, 1001L);

        assertEquals(1, repository.getTopRecords(map1, mode, 10).size());
        assertEquals(1, repository.getTopRecords(map2, mode, 10).size());

        repository.clearByMap(map1);
        assertTrue(repository.getTopRecords(map1, mode, 10).isEmpty());
        assertEquals(1, repository.getTopRecords(map2, mode, 10).size());
    }

    @Test
    void testOneTimeMigrationOnlyRunsOnce() {
        Core.settings.put(MapRecordRepository.MIGRATION_KEY, false);
        String map = "migrated-map";
        String mode = "attack";
        List<MapRecordPlayer> players = List.of(new MapRecordPlayer("u1", "Player1"));

        repository.saveAttempt(map, mode, 5000L, players, 1000L);
        assertEquals(1, repository.getTopRecords(map, mode, 10).size());

        // First init runs migration and wipes
        repository.init();
        assertTrue(repository.getTopRecords(map, mode, 10).isEmpty());
        assertTrue(Core.settings.getBool(MapRecordRepository.MIGRATION_KEY, false));

        // Insert new record after migration
        repository.saveAttempt(map, mode, 4000L, players, 2000L);
        assertEquals(1, repository.getTopRecords(map, mode, 10).size());

        // Second init (e.g. server restart) should NOT wipe
        repository.init();
        assertEquals(1, repository.getTopRecords(map, mode, 10).size());
    }
}
