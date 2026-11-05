package plugin.maprating;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import arc.util.Log;
import mindustry.gen.Iconc;
import mindustry.maps.Map;
import plugin.annotations.Component;
import plugin.utils.Tr;

@Component
public class MapRatingService {
    private final MapRatingRepository repository;
    private final ConcurrentMap<String, MapRatingStat> cache = new ConcurrentHashMap<>();

    public MapRatingService(MapRatingRepository repository) {
        this.repository = repository;
    }

    public void updateMapRating(Map map, int star) {
        if (map == null || map.file == null) {
            return;
        }
        updateMapRating(map.file.nameWithoutExtension(), star);
    }

    public void updateMapRating(String mapName, int star) {
        repository.updateRating(mapName, star);
        if (mapName != null && !mapName.isBlank()) {
            String cleanName = mapName.trim();
            repository.findByMapName(cleanName).ifPresentOrElse(
                    r -> cache.put(cleanName, new MapRatingStat(r.votes(), r.rating())),
                    () -> cache.remove(cleanName)
            );
        }
    }

    public MapRatingStat getStat(Map map) {
        if (map == null || map.file == null) {
            return new MapRatingStat(0, 0f);
        }
        return getStat(map.file.nameWithoutExtension());
    }

    public MapRatingStat getStat(String mapName) {
        if (mapName == null || mapName.isBlank()) {
            return new MapRatingStat(0, 0f);
        }
        return cache.computeIfAbsent(mapName.trim(), name ->
                repository.findByMapName(name)
                        .map(r -> new MapRatingStat(r.votes(), r.rating()))
                        .orElseGet(() -> new MapRatingStat(0, 0f))
        );
    }

    public String getAvgString(Map map) {
        float score = getStat(map).avgScore;
        return String.format(avgScoreColor(score) + "%.2f" + "[gold]" + Iconc.star, score);
    }

    public String getDisplayString(Locale locale, Map map) {
        if (map == null) {
            return Tr.t(locale, "maprating.error");
        }

        try {
            String mapName = map.file != null ? map.file.nameWithoutExtension() : map.name();
            var recordOpt = repository.findByMapName(mapName);
            int[] stars = recordOpt.map(MapRatingRecord::stars).orElseGet(() -> new int[5]);
            int totalVotes = recordOpt.map(MapRatingRecord::votes).orElse(0);
            float avg = recordOpt.map(MapRatingRecord::rating).orElse(0f);

            StringBuilder sb = new StringBuilder(map.name()).append("\n");

            if (map.author() != null) {
                sb.append(Tr.t(locale, "maprating.author", "author", map.author()))
                        .append("\n");
            }

            if (map.description() != null) {
                sb.append(map.description())
                        .append("\n");
            }

            for (int i = 0; i < 5; i++) {
                int count = stars[i];
                int starValue = i + 1;

                sb.append(getStarDisplay(starValue))
                        .append(" ")
                        .append(count)
                        .append("\n");
            }

            sb.append(Tr.t(locale, "maprating.rated", "count", totalVotes))
                    .append("\n")
                    .append(Tr.t(locale, "maprating.average_rating"));

            sb.append(avgScoreColor(avg));
            sb.append(String.format("%.2f", avg))
                    .append("[gold]")
                    .append(Iconc.star)
                    .append("\n");

            return sb.toString();
        } catch (Exception e) {
            Log.err("Failed to get map rating display", e);
            return Tr.t(Locale.ENGLISH, "maprating.error");
        }
    }

    public String avgScoreColor(float avg) {
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

    public String getStarDisplay(int star) {
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
