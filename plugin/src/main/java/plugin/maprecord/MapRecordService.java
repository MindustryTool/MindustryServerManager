package plugin.maprecord;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.StateChangeEvent;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.gamemode.Gamemode;
import plugin.gamemode.GamemodeCondition;
import plugin.gamemode.flood.FloodWonEvent;
import plugin.session.SessionService;
import plugin.utils.TimeUtils;
import plugin.utils.Tr;
import plugin.utils.Utils;

@Component
@ConditionOn(value = GamemodeCondition.class, args = {"attack", "ziger", "flood", "survival", "TowerDefense"})
@RequiredArgsConstructor
public class MapRecordService {
    private final MapRecordRepository repository;
    private final SessionService sessionService;

    private Instant mapStartedAt = Instant.now();
    private Instant pauseStartedAt = null;

    public Instant getMapStartedAt() {
        if (pauseStartedAt != null) {
            return mapStartedAt.plus(Duration.between(pauseStartedAt, Instant.now()));
        }
        return mapStartedAt;
    }

    public String buildRankString(Locale locale) {
        var map = Vars.state.map;
        if (map == null || map.file == null) {
            return Tr.t(locale, "rank.no_record");
        }
        var mapName = map.file.nameWithoutExtension();
        var best = repository.getBestRecord(mapName, Gamemode.current());
        if (best.isEmpty()) {
            return Tr.t(locale, "rank.no_record");
        }
        MapRecord record = best.get();
        List<String> playerNames = record.players().stream().map(MapRecordPlayer::name).toList();
        return Tr.t(locale, "rank.title",
                "map", map.name(),
                "time", TimeUtils.toString(Duration.ofMillis(record.durationMs())),
                "players", String.join(", ", playerNames));
    }

    @Listener
    public void onPlayEvent(PlayEvent event) {
        mapStartedAt = Instant.now();
        pauseStartedAt = Vars.state.isPaused() ? Instant.now() : null;

        Utils.forEachPlayerLocale((locale, players) -> {
            String msg = buildRankString(locale);
            for (var p : players) {
                p.sendMessage(msg);
            }
        });
    }

    @Listener
    public void onStateChangeEvent(StateChangeEvent event) {
        if (Vars.state.isPaused()) {
            if (pauseStartedAt == null) {
                pauseStartedAt = Instant.now();
            }
        } else {
            if (pauseStartedAt != null) {
                mapStartedAt = mapStartedAt.plus(Duration.between(pauseStartedAt, Instant.now()));
                pauseStartedAt = null;
            }
        }
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

        String currentMode = Gamemode.current();
        if (Gamemode.active("attack", "ziger", "survival", "TowerDefense")) {
            recordCompletion(currentMode);
        }
    }

    @Listener
    public void onFloodWon(FloodWonEvent event) {
        if (Gamemode.active("flood")) {
            recordCompletion("flood");
        }
    }

    public void recordCompletion(String gamemode) {
        if (Vars.state.map == null || Vars.state.map.file == null) {
            return;
        }

        long durationMs = Duration.between(getMapStartedAt(), Instant.now()).toMillis();
        String mapName = Vars.state.map.file.nameWithoutExtension();

        List<MapRecordPlayer> players = new ArrayList<>();
        if (sessionService != null) {
            sessionService.get().values().stream()
                    .map(session -> new MapRecordPlayer(session.player.uuid(), session.player.name()))
                    .forEach(players::add);
        }

        if (repository != null) {
            var resultOpt = repository.saveAttempt(mapName, gamemode, durationMs, players, System.currentTimeMillis());
            if (resultOpt.isPresent()) {
                RecordSaveResult result = resultOpt.get();
                if (result.isNewBest()) {
                    Utils.forEachPlayerLocale((locale, ps) -> {
                        String msg = Tr.t(locale, "rank.new_best_time",
                                "time", TimeUtils.toString(Duration.ofMillis(durationMs)));
                        for (var p : ps) {
                            p.sendMessage(msg);
                        }
                    });
                } else {
                    Utils.forEachPlayerLocale((locale, ps) -> {
                        String msg = Tr.t(locale, "rank.new_rank",
                                "rank", String.valueOf(result.rank()),
                                "time", TimeUtils.toString(Duration.ofMillis(durationMs)));
                        for (var p : ps) {
                            p.sendMessage(msg);
                        }
                    });
                }
            }
        }

        if (sessionService != null) {
            sessionService.each(session -> {
                long playerPlayedDuration = Duration.between(Instant.ofEpochMilli(session.joinedAt), Instant.now()).abs().toSeconds();
                long mapDuration = Duration.between(getMapStartedAt(), Instant.now()).abs().toSeconds();
                float playerParticipation = mapDuration > 0 ? (float) playerPlayedDuration / mapDuration : 1f;

                if (playerPlayedDuration > mapDuration || playerParticipation > 1f) {
                    playerParticipation = 1f;
                }

                session.expGainBonus = playerParticipation;
                session.player.sendMessage(Tr.t(session, "rank.win_bonus",
                        "percent", String.valueOf(playerParticipation * 100)));
            });
        }
    }
}
