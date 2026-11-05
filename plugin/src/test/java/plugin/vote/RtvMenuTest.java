package plugin.vote;

import java.util.HashMap;

import arc.files.Fi;
import arc.struct.Seq;
import arc.struct.StringMap;
import org.junit.jupiter.api.Test;
import mindustry.maps.Map;
import plugin.maprating.MapRatingRepository;
import plugin.maprating.MapRatingService;
import plugin.maprating.MapRatingStat;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RtvMenuTest {

    private Map createMap(String filename, String name) {
        StringMap tags = new StringMap();
        tags.put("name", name);
        return new Map(new Fi(filename), 100, 100, tags, true, 0, 146);
    }

    private static class StubMapRatingService extends MapRatingService {
        private final java.util.Map<String, MapRatingStat> stats = new HashMap<>();

        public StubMapRatingService() {
            super((MapRatingRepository) null);
        }

        public void putStat(String mapName, MapRatingStat stat) {
            stats.put(mapName, stat);
        }

        @Override
        public MapRatingStat getStat(Map map) {
            if (map == null || map.file == null) {
                return new MapRatingStat(0, 0f);
            }
            return stats.getOrDefault(map.file.nameWithoutExtension(), new MapRatingStat(0, 0f));
        }
    }

    @Test
    void testUnratedMapsPrioritizedFirst() {
        StubMapRatingService service = new StubMapRatingService();
        Map ratedMap = createMap("rated.msav", "Rated Map");
        Map unratedMap = createMap("unrated.msav", "Unrated Map");

        service.putStat("rated", new MapRatingStat(10, 4.8f));
        service.putStat("unrated", new MapRatingStat(0, 0.0f));

        Seq<Map> maps = Seq.with(ratedMap, unratedMap);
        var sorted = RtvMenu.prepareSortedEntries(maps, service);

        assertEquals("Unrated Map", sorted.get(0).map().name());
        assertEquals("Rated Map", sorted.get(1).map().name());
    }

    @Test
    void testTiesBrokenByVotesThenName() {
        StubMapRatingService service = new StubMapRatingService();
        Map mapA = createMap("a.msav", "Map Bravo");
        Map mapB = createMap("b.msav", "Map Alpha");
        Map mapC = createMap("c.msav", "Map Charlie");

        // mapA and mapB have same score and same votes -> alphabetical by name (Alpha before Bravo)
        service.putStat("a", new MapRatingStat(5, 4.5f));
        service.putStat("b", new MapRatingStat(5, 4.5f));

        // mapC has same score but more votes -> should come before both mapA and mapB
        service.putStat("c", new MapRatingStat(10, 4.5f));

        Seq<Map> maps = Seq.with(mapA, mapB, mapC);
        var sorted = RtvMenu.prepareSortedEntries(maps, service);

        assertEquals("Map Charlie", sorted.get(0).map().name());
        assertEquals("Map Alpha", sorted.get(1).map().name());
        assertEquals("Map Bravo", sorted.get(2).map().name());
    }

    @Test
    void testTimSortContractStabilityWithLargeCatalog() {
        StubMapRatingService service = new StubMapRatingService();
        Seq<Map> maps = new Seq<>();

        for (int i = 0; i < 150; i++) {
            String filename = "map_" + i + ".msav";
            String name = "Map " + String.format("%03d", i);
            Map map = createMap(filename, name);
            maps.add(map);

            // Mix different scores: 0 (unrated), duplicated ratings, varying votes
            float score = (i % 6 == 0) ? 0f : (1.0f + (i % 5));
            int votes = i % 10;
            service.putStat("map_" + i, new MapRatingStat(votes, score));
        }

        // Sorting must complete cleanly without throwing IllegalArgumentException
        var sorted = RtvMenu.prepareSortedEntries(maps, service);
        assertEquals(150, sorted.size);
    }
}
