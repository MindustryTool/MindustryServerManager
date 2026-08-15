package plugin.grief;

import plugin.utils.I18n;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import mindustry.Vars;
import mindustry.game.EventType.BlockBuildBeginEvent;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Player;
import mindustry.world.Tile;
import plugin.annotations.ClientCommand;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;
import plugin.annotations.Listener;
import plugin.session.Session;
import plugin.utils.TimeUtils;
import plugin.utils.Utils;

@Component
public class TileLogger {

    private static final int MAX_ENTRIES_PER_TILE = 100;
    private static final int MAX_INSPECT_ENTRIES = 5;
    private static final int ENTRY_PAD_LENGTH = 28;
    private static final String SEPARATOR = "[gray]" + "─".repeat(36) + "[]";

    private final ConcurrentHashMap<Integer, Deque<TileLogEntry>> logsByPos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Player> pendingBreaks = new ConcurrentHashMap<>();
    private final Set<String> inspectAdmins = ConcurrentHashMap.newKeySet();

    @Init
    public void init() {
        logsByPos.clear();
        pendingBreaks.clear();
        inspectAdmins.clear();
    }

    @Destroy
    public void destroy() {
        logsByPos.clear();
        pendingBreaks.clear();
        inspectAdmins.clear();
    }

    @Listener
    public void onBlockBuildEnd(BlockBuildEndEvent event) {
        if (event.breaking || !event.unit.isPlayer()) {
            return;
        }

        var player = event.unit.getPlayer();
        append(event.tile.pos(), TileLogEntry.place(player, event.tile, mapName()));
    }

    @Listener
    public void onBlockBuildBegin(BlockBuildBeginEvent event) {
        if (!event.breaking || !event.unit.isPlayer()) {
            return;
        }

        pendingBreaks.put(event.tile.pos(), event.unit.getPlayer());
    }

    @Listener
    public void onBlockDestroy(BlockDestroyEvent event) {
        var pos = event.tile.pos();
        var player = pendingBreaks.remove(pos);
        append(pos, TileLogEntry.destroy(player, event.tile, mapName()));
    }

    @Listener
    public void onWorldLoad(WorldLoadEvent event) {
        logsByPos.clear();
        pendingBreaks.clear();
    }

    @ClientCommand(name = "tilelog", description = "Toggle tile log inspection on tap", admin = true)
    public void toggleInspect(Session session) {
        var uuid = session.player.uuid();

        if (inspectAdmins.add(uuid)) {
            session.player.sendMessage(
                    I18n.t(session, "[green]", "@Tile logger", "[yellow]", "@enabled - tap any tile to inspect its history"));
        } else {
            inspectAdmins.remove(uuid);
            session.player.sendMessage(
                    I18n.t(session, "[scarlet]", "@Tile logger", "[yellow]", "@disabled"));
        }
    }

    @Listener
    public void onTap(TapEvent event) {
        var player = event.player;

        if (!inspectAdmins.contains(player.uuid())) {
            return;
        }

        var entries = logsByPos.get(event.tile.pos());

        if (entries == null || entries.isEmpty()) {
            player.sendMessage(I18n.t(player, "[gray]", "@No records for this tile"));
            return;
        }

        player.sendMessage(SEPARATOR);
        player.sendMessage(I18n.t(player, "[accent]", "@Tile log",
                "[white](" + event.tile.x + ", " + event.tile.y + ")"));

        var now = Instant.now().toEpochMilli();
        int shown = 0;
        var it = entries.descendingIterator();

        while (it.hasNext() && shown < MAX_INSPECT_ENTRIES) {
            var entry = it.next();
            shown++;

            var action = "place".equals(entry.action())
                    ? "[green]placed[]"
                    : "[red]destroyed[]";
            var by = entry.name() != null
                    ? "[white]" + entry.name() + "[]"
                    : "[gray]unit[]";
            var ago = TimeUtils.toString(Duration.ofMillis(now - entry.time()));

            player.sendMessage(Utils.padRight(action + " [accent]" + entry.block() + "[]", ENTRY_PAD_LENGTH)
                    + "by " + by + " [gray]" + ago + " ago[]");
        }

        if (entries.size() > shown) {
            player.sendMessage(
                    I18n.t(player, "[gray]", "@and", entries.size() - shown, "@more entries[]"));
        }

        player.sendMessage(SEPARATOR);
    }

    private void append(int pos, TileLogEntry entry) {
        var deque = logsByPos.computeIfAbsent(pos, p -> new ArrayDeque<>());
        deque.addLast(entry);

        while (deque.size() > MAX_ENTRIES_PER_TILE) {
            deque.removeFirst();
        }
    }

    private String mapName() {
        return Vars.state.map == null ? "unknown" : Vars.state.map.name();
    }

    public record TileLogEntry(String uuid, String name, String block, int x, int y, String map, String action, long time) {

        public static TileLogEntry place(Player player, Tile tile, String map) {
            return of(player, tile, map, "place");
        }

        public static TileLogEntry destroy(Player player, Tile tile, String map) {
            return of(player, tile, map, "destroy");
        }

        private static TileLogEntry of(Player player, Tile tile, String map, String action) {
            var block = tile.block() == null ? "?" : tile.block().localizedName;

            return new TileLogEntry(
                    player != null ? player.uuid() : null,
                    player != null ? player.name : null,
                    block,
                    tile.x,
                    tile.y,
                    map,
                    action,
                    Instant.now().toEpochMilli());
        }
    }
}