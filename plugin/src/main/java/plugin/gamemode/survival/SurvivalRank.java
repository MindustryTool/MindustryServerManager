package plugin.gamemode.survival;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import arc.Core;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.PlayerJoin;
import plugin.annotations.Gamemode;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.session.SessionService;
import plugin.utils.JsonUtils;
import plugin.utils.TimeUtils;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Gamemode({ "survival", "TowerDefense" })
@RequiredArgsConstructor
public class SurvivalRank {
    private Instant mapStartedAt = Instant.now();

    private final SessionService sessionService;

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

    private String buildRankString(Locale locale) {
        var map = Vars.state.map;
        var mapName = map.file.nameWithoutExtension();

        var data = Core.settings.getString(KEY, "{}");
        var wrapper = JsonUtils.readJsonAsClass(data, DataWrapper.class);
        var history = wrapper.data.get(mapName);

        if (history == null) {
            return Tr.t(locale, "rank.no_record");
        }

        return Tr.t(locale, "rank.title", "map", map.name(),
                "time", TimeUtils.toString(Duration.ofMillis(history.surviveTimeMs)),
                "players", String.join(", ", history.players));
    }

    @Listener
    public void onPlayEvent(PlayEvent event) {
        mapStartedAt = Instant.now();

        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = buildRankString(locale);
            for (var p : players) {
                p.sendMessage(msg);
            }
        });
    }

    @Listener
    public void onPlayerJoin(PlayerJoin event) {
        event.player.sendMessage(buildRankString(Utils.parseLocale(event.player.locale())));
    }

    @Listener
    public void onGameOver(GameOverEvent event) {
        if (event.winner != Vars.state.rules.defaultTeam) {
            return;
        }

        sessionService.each(session -> {
            long playerPlayedDuration = Duration.between(Instant.ofEpochMilli(session.joinedAt), Instant.now()).abs().toSeconds();
            long mapDuration = Duration.between(mapStartedAt, Instant.now()).abs().toSeconds();
            float playerParticipation = (float) playerPlayedDuration / mapDuration;

            if (playerPlayedDuration > mapDuration) {
                playerParticipation = 1;
            }

            session.expGainBonus = playerParticipation;
            session.player.sendMessage(Tr.t(session, "rank.win_bonus", "percent",
                    String.valueOf(playerParticipation * 100)));
        });

        var data = Core.settings.getString(KEY, "{}");
        var wrapper = JsonUtils.readJsonAsClass(data, DataWrapper.class);
        var map = Vars.state.map;
        var mapName = map.file.nameWithoutExtension();
        var players = new ArrayList<String>();

        sessionService.get().values().stream().map(v -> v.player.name()).forEach(v -> players.add(v));

        wrapper.data.compute(mapName, (k, v) -> {
            long surviveTimeMs = Duration.between(mapStartedAt, Instant.now()).toMillis();

            if (v == null) {
                v = new SurvivalRankData();
            }

            if (v.surviveTimeMs < surviveTimeMs) {
                v.surviveTimeMs = surviveTimeMs;
                v.players = players;

                Utils.forEachPlayerLocale((locale, ps) -> {
                    String msg = Tr.t(locale, "rank.new_best_time", "time",
                            TimeUtils.toString(Duration.ofMillis(surviveTimeMs)));
                    for (var p : ps) {
                        p.sendMessage(msg);
                    }
                });
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
