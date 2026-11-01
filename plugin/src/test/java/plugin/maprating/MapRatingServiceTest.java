package plugin.maprating;

import java.nio.file.Path;

import arc.Core;
import arc.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;
import plugin.database.schema.MapRatings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MapRatingServiceTest {

    @TempDir
    Path tempDir;

    private Database database;
    private MapRatingRepository repository;
    private MapRatingService service;

    @BeforeEach
    void setUp() {
        Core.settings = new Settings();
        Database.setTestPath(tempDir.resolve("maprating_test.db"));
        database = new Database();
        repository = new MapRatingRepository(database);
        repository.createTableIfNotExists();
        service = new MapRatingService(repository);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
        Database.clearTestPath();
    }

    @Test
    void testUnratedMapReturnsZero() {
        var stat = service.getStat("unknown_map");
        assertEquals(0, stat.totalVotes);
        assertEquals(0f, stat.avgScore);
    }

    @Test
    void testVoteUpdatesAndRecalculation() {
        service.updateMapRating("desert_war", 5);
        var stat1 = service.getStat("desert_war");
        assertEquals(1, stat1.totalVotes);
        assertEquals(5.0f, stat1.avgScore, 0.001);

        service.updateMapRating("desert_war", 3);
        var stat2 = service.getStat("desert_war");
        assertEquals(2, stat2.totalVotes);
        assertEquals(4.0f, stat2.avgScore, 0.001);

        service.updateMapRating("desert_war", 1);
        var stat3 = service.getStat("desert_war");
        assertEquals(3, stat3.totalVotes);
        assertEquals(3.0f, stat3.avgScore, 0.001);

        // Invalid stars should be ignored
        service.updateMapRating("desert_war", 0);
        service.updateMapRating("desert_war", 6);
        var statAfterInvalid = service.getStat("desert_war");
        assertEquals(3, statAfterInvalid.totalVotes);

        // Verify database persistence directly
        var row = database.db().select(
                        MapRatings.MAP_NAME, MapRatings.STAR_1, MapRatings.STAR_3, MapRatings.STAR_5,
                        MapRatings.VOTES, MapRatings.RATING)
                .from(MapRatings.TABLE)
                .where(MapRatings.MAP_NAME.eq("desert_war"))
                .fetchOne()
                .orElse(null);

        assertNotNull(row);
        assertEquals("desert_war", row.get(MapRatings.MAP_NAME));
        assertEquals(1, row.get(MapRatings.STAR_1));
        assertEquals(1, row.get(MapRatings.STAR_3));
        assertEquals(1, row.get(MapRatings.STAR_5));
        assertEquals(3, row.get(MapRatings.VOTES));
        assertEquals(3.0f, row.get(MapRatings.RATING), 0.001);
    }

    @Test
    void testDisplayFormattingHelpers() {
        assertEquals("[scarlet]", service.avgScoreColor(0.5f));
        assertEquals("[orange]", service.avgScoreColor(1.5f));
        assertEquals("[yellow]", service.avgScoreColor(2.5f));
        assertEquals("[lime]", service.avgScoreColor(3.5f));
        assertEquals("[green]", service.avgScoreColor(4.5f));

        String starDisplay = service.getStarDisplay(3);
        assertNotNull(starDisplay);
        assertEquals(true, starDisplay.contains("[gold]"));
        assertEquals(true, starDisplay.contains("[gray]"));
    }
}
