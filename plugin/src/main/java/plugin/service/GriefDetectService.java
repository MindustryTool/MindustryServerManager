package plugin.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.BlockBuildBeginEvent;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.gen.Player;
import plugin.annotations.Component;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.repository.SessionRepository;
import plugin.utils.Utils;

@Component
@RequiredArgsConstructor
public class GriefDetectService {

    private final SessionRepository sessionRepository;
    private final SessionHandler sessionHandler;

    private final ConcurrentHashMap<Player, Long> scores = new ConcurrentHashMap<>();

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    public void clear() {
        var updated = new ConcurrentHashMap<Player, Long>(scores);

        for (var entry : scores.entrySet()) {
            if (entry.getValue() > 0) {
                continue;
            }

            if (entry.getValue() > 100) {
                Utils.forEachPlayerLocale((locale, players) -> {
                    var message = I18n.t(locale, "[scarlet]", "@Suspect griefer", entry.getKey().name);
                    for (var player : players) {
                        player.sendMessage(message);
                    }
                });
            }

            updated.put(entry.getKey(), entry.getValue() + 1);

            sessionHandler.get(entry.getKey()).ifPresent(session -> {
                if (!session.getData().griefer) {
                    session.getData().griefer = true;
                    sessionRepository.markDirty(session.player.uuid());
                }
            });
        }

        scores.putAll(updated);
    }

    @Listener
    public void onBlockBuildBegin(BlockBuildBeginEvent event) {
        if (event.unit.isPlayer()) {
            var player = event.unit.getPlayer();
            var score = scores.getOrDefault(player, 0L);

            if (event.breaking) {
                scores.put(player, score - 1);
            }
        }
    }

    @Listener
    public void onBlockBuildEnd(BlockBuildEndEvent event) {
        if (!event.breaking) {
            return;
        }

        if (event.unit.isPlayer()) {
            var player = event.unit.getPlayer();
            var score = scores.getOrDefault(player, 0l);

            if (event.breaking) {
                scores.put(player, score - 1);
            } else {
                scores.put(player, score + 1);
            }
        }
    }
}
