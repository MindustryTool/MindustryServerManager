package plugin.gamemode.survival;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import arc.Core;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.gen.Call;
import plugin.annotations.Gamemode;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.service.SessionHandler;
import plugin.utils.JsonUtils;
import plugin.utils.TimeUtils;

@Gamemode({ "survival", "TowerDefense" })
@RequiredArgsConstructor
public class SurvivalRank {
    private Instant mapStartedAt = Instant.now();

    private final SessionHandler sessionHandler;

    private final String KEY = "survival-rank";
    private final String version = "1";

    @Init
    private void init() {
        String VERSION_KEY = "rank-version";
        String currentVersion = Core.settings.getString(VERSION_KEY, "0");
        if (!currentVersion.equals(version)) {
            Core.settings.remove(KEY);
            Core.settings.put(VERSION_KEY, version);
        }
    }

    private String buildRankString() {
        var map = Vars.state.map;
        var mapName = map.file.nameWithoutExtension();

        var data = Core.settings.getString(KEY, "{}");
        var wrapper = JsonUtils.readJsonAsClass(data, DataWrapper.class);
        var history = wrapper.data.get(mapName);

        if (history == null) {
            return "No record for this map";
        }

        return "Map: " + map.name() + "\nBest time: "
                + TimeUtils.toString(Duration.ofMillis(history.surviveTimeMs))
                + "\nPlayers: " + String.join(", ", history.players);
    }

    @Listener
    public void onPlayEvent(PlayEvent event) {
        mapStartedAt = Instant.now();

        Call.sendMessage(buildRankString());
    }

    @Listener
    public void onPlayerJoin(PlayerJoin event) {
        event.player.sendMessage(buildRankString());
    }

    @Listener
    public void onGameOver(GameOverEvent event) {
        if (event.winner != Vars.state.rules.defaultTeam) {
            return;
        }

        var data = Core.settings.getString(KEY, "{}");
        var wrapper = JsonUtils.readJsonAsClass(data, DataWrapper.class);
        var map = Vars.state.map;
        var mapName = map.file.nameWithoutExtension();
        var players = new ArrayList<String>();

        sessionHandler.get().values().stream().map(v -> v.player.name()).forEach(v -> players.add(v));

        wrapper.data.compute(mapName, (k, v) -> {
            long surviveTimeMs = Duration.between(mapStartedAt, Instant.now()).toMillis();

            if (v == null) {
                v = new SurvivalRankData();
            }

            if (v.surviveTimeMs < surviveTimeMs) {
                v.surviveTimeMs = surviveTimeMs;
                v.players = players;

                Call.sendMessage("New best time: " + TimeUtils.toString(Duration.ofMillis(surviveTimeMs)));
            }

            return v;
        });

        Core.settings.put(KEY, JsonUtils.toJsonString(wrapper));
    }

    @Data
    private static class DataWrapper {
        private HashMap<String, SurvivalRankData> data = new HashMap<>();
    }

    @Data
    private static class SurvivalRankData {
        private long surviveTimeMs = Long.MAX_VALUE;
        private List<String> players = new ArrayList<>();
    }
}
