package plugin.maprating;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import arc.Core;
import arc.util.Log;
import mindustry.gen.Iconc;
import mindustry.game.EventType.PlayEvent;
import mindustry.maps.Map;
import plugin.Tasks;
import plugin.annotations.Component;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.utils.Tr;

@Component
public class MapRatingService {
    private static final MapRatingStat EMPTY_STAT = new MapRatingStat(0, 0f);

    private final MapRatingRepository repository;
    private final ConcurrentMap<String, MapRatingRecord> cache = new ConcurrentHashMap<>();

    public MapRatingService(MapRatingRepository repository) {
        this.repository = repository;
    }

    @Init
    public void init() {
        reloadCache();
    }

    @Listener(PlayEvent.class)
    public void onPlayEvent(PlayEvent event) {
        reloadCache();
    }

    private void reloadCache() {
        if (repository == null) {
            return;
        }
        Tasks.io("load map ratings", () -> {
            List<MapRatingRecord> records = repository.findAll();
            Core.app.post(() -> {
                cache.clear();
                for (MapRatingRecord record : records) {
                    cache.put(record.mapName(), record);
                }
            });
        });
    }

    public void updateMapRating(Map map, int star) {
        if (map == null || map.file == null) {
            return;
        }
        updateMapRating(map.file.nameWithoutExtension(), star);
    }

    public void updateMapRating(String mapName, int star) {
        if (mapName == null || mapName.isBlank() || star < 1 || star > 5) {
            return;
        }
        String cleanName = mapName.trim();

        MapRatingRecord current = cache.get(cleanName);
        int[] stars = current == null ? new int[5] : current.stars().clone();
        stars[star - 1]++;

        int totalVotes = 0;
        float totalScore = 0f;
        for (int i = 0; i < 5; i++) {
            totalVotes += stars[i];
            totalScore += stars[i] * (i + 1);
        }
        float avg = totalVotes == 0 ? 0f : totalScore / totalVotes;

        MapRatingRecord updated = new MapRatingRecord(cleanName, stars[0], stars[1], stars[2], stars[3], stars[4], totalVotes, avg);
        cache.put(cleanName, updated);

        if (repository != null) {
            Tasks.io("save map rating " + cleanName, () -> repository.updateRating(cleanName, star));
        }
    }

    public MapRatingStat getStat(Map map) {
        if (map == null || map.file == null) {
            return EMPTY_STAT;
        }
        return getStat(map.file.nameWithoutExtension());
    }

    public MapRatingStat getStat(String mapName) {
        if (mapName == null || mapName.isBlank()) {
            return EMPTY_STAT;
        }
        MapRatingRecord record = cache.get(mapName.trim());
        if (record == null) {
            return EMPTY_STAT;
        }
        return new MapRatingStat(record.votes(), record.rating());
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
            MapRatingRecord record = cache.get(mapName == null ? null : mapName.trim());
            int[] stars = record == null ? new int[5] : record.stars();
            int totalVotes = record == null ? 0 : record.votes();
            float avg = record == null ? 0f : record.rating();

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