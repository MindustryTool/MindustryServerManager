package plugin.maprating;

import java.util.Optional;

import arc.util.Log;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.database.Database;
import plugin.database.schema.MapRatings;

@Component
public class MapRatingRepository {

    private final Database database;

    public MapRatingRepository(Database database) {
        this.database = database;
    }

    @Init
    public void init() {
        createTableIfNotExists();
    }

    public void createTableIfNotExists() {
        try {
            database.db().createTableIfNotExists(MapRatings.TABLE);
        } catch (Exception e) {
            Log.err("Failed to create map_ratings table", e);
        }
    }

    public Optional<MapRatingRecord> findByMapName(String mapName) {
        if (mapName == null || mapName.isBlank()) {
            return Optional.empty();
        }

        try {
            var row = database.db().select(
                            MapRatings.MAP_NAME,
                            MapRatings.STAR_1,
                            MapRatings.STAR_2,
                            MapRatings.STAR_3,
                            MapRatings.STAR_4,
                            MapRatings.STAR_5,
                            MapRatings.VOTES,
                            MapRatings.RATING
                    )
                    .from(MapRatings.TABLE)
                    .where(MapRatings.MAP_NAME.eq(mapName.trim()))
                    .fetchOne()
                    .orElse(null);

            if (row == null) {
                return Optional.empty();
            }

            return Optional.of(new MapRatingRecord(
                    row.get(MapRatings.MAP_NAME),
                    row.get(MapRatings.STAR_1),
                    row.get(MapRatings.STAR_2),
                    row.get(MapRatings.STAR_3),
                    row.get(MapRatings.STAR_4),
                    row.get(MapRatings.STAR_5),
                    row.get(MapRatings.VOTES),
                    row.get(MapRatings.RATING)
            ));
        } catch (Exception e) {
            Log.err("Error reading map rating for: @", mapName, e);
            return Optional.empty();
        }
    }

    public synchronized void updateRating(String mapName, int star) {
        if (mapName == null || mapName.isBlank() || star < 1 || star > 5) {
            return;
        }

        String cleanName = mapName.trim();
        int[] stars = findByMapName(cleanName)
                .map(MapRatingRecord::stars)
                .orElseGet(() -> new int[5]);

        stars[star - 1]++;

        int totalVotes = 0;
        float totalScore = 0f;
        for (int i = 0; i < 5; i++) {
            int count = stars[i];
            totalVotes += count;
            totalScore += count * (i + 1);
        }
        float avg = totalVotes == 0 ? 0f : totalScore / totalVotes;

        try {
            database.db().insert(MapRatings.TABLE)
                    .set(MapRatings.MAP_NAME, cleanName)
                    .set(MapRatings.STAR_1, stars[0])
                    .set(MapRatings.STAR_2, stars[1])
                    .set(MapRatings.STAR_3, stars[2])
                    .set(MapRatings.STAR_4, stars[3])
                    .set(MapRatings.STAR_5, stars[4])
                    .set(MapRatings.VOTES, totalVotes)
                    .set(MapRatings.RATING, avg)
                    .onConflictDoUpdate(MapRatings.MAP_NAME,
                            MapRatings.STAR_1, MapRatings.STAR_2, MapRatings.STAR_3,
                            MapRatings.STAR_4, MapRatings.STAR_5, MapRatings.VOTES, MapRatings.RATING)
                    .execute();
        } catch (Exception e) {
            Log.err("Error updating map rating for: @", cleanName, e);
        }
    }
}
