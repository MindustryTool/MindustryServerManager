package plugin.handler;

import java.util.HashMap;

import arc.Core;
import arc.util.Log;
import lombok.Data;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.utils.JsonUtils;

public class MapRating {
    private static final String RATING_PERSIT_KEY = "server.map-rating";

    @Data
    public static class MapRatingData {
        public HashMap<String, MapRatingEntry> mapRatings = new HashMap<>();
    }

    @Data
    public static class MapRatingEntry {
        public int[] stars = new int[5];

        public float avg() {
            int totalVotes = 0;
            int totalScore = 0;

            for (int i = 0; i < 5; i++) {
                int count = stars[i];
                int starValue = i + 1;

                totalVotes += count;
                totalScore += count * starValue;
            }

            return totalVotes == 0 ? 0f : (float) totalScore / totalVotes;
        }
    }

    private static MapRatingData load() {
        try {
            String json = Core.settings.getString(RATING_PERSIT_KEY, "{}");
            return JsonUtils.readJsonAsClass(json, MapRatingData.class);
        } catch (Exception e) {
            Log.warn("Rating data invalid, clearing");
            return new MapRatingData();
        }
    }

    private static void save(MapRatingData data) {
        Core.settings.put(RATING_PERSIT_KEY, JsonUtils.toJsonString(data));
    }

    public synchronized static void updateMapRating(Map map, int star) {
        if (star < 1 || star > 5)
            return;

        try {
            String mapId = map.file.nameWithoutExtension();
            MapRatingData data = load();

            MapRatingEntry entry = data.mapRatings
                    .computeIfAbsent(mapId, id -> new MapRatingEntry());

            entry.stars[star - 1]++;

            save(data);

        } catch (Exception e) {
            Log.err("Failed to update map rating", e);
        }
    }

    public static float getAvg(Map map) {
        try {
            String mapId = map.file.nameWithoutExtension();
            MapRatingData data = load();

            MapRatingEntry entry = data.mapRatings.getOrDefault(mapId, new MapRatingEntry());
            return entry.avg();
        } catch (Exception e) {
            Log.err(e);
            return 0;
        }
    }

    public static String getAvgString(Map map) {
        float score = getAvg(map);

        return String.format(avgScoreColor(score) + "%.2f" + "[gold]" + Iconc.star, score);
    }

    public static String getDisplayString(Map map) {
        try {
            String mapId = map.file.nameWithoutExtension();
            MapRatingData data = load();

            MapRatingEntry entry = data.mapRatings.get(mapId);

            if (entry == null) {
                entry = new MapRatingEntry();
            }

            StringBuilder sb = new StringBuilder(map.name()).append("\n");

            int totalVotes = 0;
            int totalScore = 0;

            for (int i = 0; i < 5; i++) {
                int count = entry.stars[i];
                int starValue = i + 1;

                totalVotes += count;
                totalScore += count * starValue;

                sb.append(getStarDisplay(starValue))
                        .append(" ")
                        .append(count)
                        .append("\n");
            }

            float avg = totalVotes == 0 ? 0f : (float) totalScore / totalVotes;

            sb.append("[gray]Rated: ")
                    .append(totalVotes)
                    .append(" times\n")
                    .append("Average rating: ");

            sb.append(avgScoreColor(avg));
            sb.append(String.format("%.2f", avg))
                    .append("[gold]")
                    .append(Iconc.star)
                    .append("\n");

            return sb.toString();

        } catch (Exception e) {
            Log.err("Failed to get map rating", e);
            return "Error";
        }
    }

    public static String avgScoreColor(float avg) {
        if (avg < 1) {
            return "[scarlet]";
        } else if (avg < 2) {
            return "[orange]";
        } else if (avg < 3) {
            return "[yellow]";
        } else if (avg < 4) {
            return "[lime]";
        } else {
            return "[green]";
        }
    }

    public static String getStarDisplay(int star) {
        StringBuilder sb = new StringBuilder("[gold]");

        for (int i = 0; i < star; i++) {
            sb.append(Iconc.star);
        }

        sb.append("[gray]");

        for (int i = 0; i < 5 - star; i++) {
            sb.append(Iconc.star);
        }

        return sb.toString();
    }
}
