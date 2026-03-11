package plugin.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import arc.struct.Seq;
import lombok.RequiredArgsConstructor;
import mindustry.game.EventType.BlockBuildBeginEvent;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.gen.Player;
import plugin.Cfg;
import plugin.annotations.Component;
import plugin.annotations.ConditionOn;
import plugin.annotations.Listener;
import plugin.annotations.Schedule;
import plugin.menus.GreifLoginMenu;
import plugin.type.Session;
import plugin.utils.Utils;

@Component
@ConditionOn(Cfg.OnOfficial.class)
@RequiredArgsConstructor
public class GriefDetectService {

    private final SessionHandler sessionHandler;
    private final ApiGateway apiGateway;

    private final ConcurrentHashMap<Player, Long> scores = new ConcurrentHashMap<>();

    @Schedule(fixedDelay = 1, unit = TimeUnit.SECONDS)
    public void clear() {
        var updated = new ConcurrentHashMap<Player, Long>(scores);
        var removed = new Seq<Player>();

        for (var entry : scores.entrySet()) {
            if (entry.getValue() > 0) {
                removed.add(entry.getKey());
                continue;
            }

            updated.put(entry.getKey(), entry.getValue() + 5);

            if (entry.getValue() > 100) {
                Session session = sessionHandler.get(entry.getKey()).orElseThrow();
                var isLoggedIn = session.isLoggedIn();

                Utils.forEachPlayerLocale((locale, players) -> {
                    var message = I18n.t(locale, "[scarlet]", "@Suspect griefer", entry.getKey().name);
                    for (var player : players) {
                        player.sendMessage(message);
                    }
                });

                if (isLoggedIn) {
                    continue;
                }

                var login = apiGateway.login(entry.getKey());

                new GreifLoginMenu().send(session, login.getLoginLink());
            }

        }

        scores.putAll(updated);

        for (var key : removed) {
            scores.remove(key);
        }
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
