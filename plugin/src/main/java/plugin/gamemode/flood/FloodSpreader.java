package plugin.gamemode.flood;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;

import arc.math.Mathf;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.world.Block;
import mindustry.world.Tile;

/**
 * Event-driven flood simulation. Each floodable tile owns at most one entry in a
 * min-heap keyed by its next activation time (evolution deadline or damage pulse),
 * so ticks with no due events cost O(1) and allocate nothing.
 */
public class FloodSpreader {

    private static final long DAMAGE_PULSE_MILLIS = 1000;
    private static final long ORPHAN_SWEEP_MILLIS = 5000;
    private static final int INITIAL_HEAP_CAPACITY = 256;

    private final FloodConfig config;
    private final HashMap<Block, FloodConfig.FloodTile> tierByBlock = new HashMap<>();

    /** Next activation deadline per tile position; 0 means none pending. */
    private long[] deadlines = new long[0];
    /** Tile positions with a live heap entry. */
    private BitSet scheduled = new BitSet();
    /** Enemy structures anchored to a first-tier pulse by the seeding pass. */
    private BitSet seededPulses = new BitSet();

    // Min-heap of pending events as parallel primitive arrays, ordered by time.
    private long[] heapAt = new long[INITIAL_HEAP_CAPACITY];
    private int[] heapPos = new int[INITIAL_HEAP_CAPACITY];
    private int heapSize = 0;

    private final HashMap<Block, IntSeq> pendingUpdates = new HashMap<>();
    private int pendingCount = 0;

    // Scratch state for the periodic connectivity sweep.
    private final IntSeq sweepQueue = new IntSeq();
    private BitSet reachable = new BitSet();
    private long nextSweepAt = 0;

    private int width = 0;
    private int height = 0;

    public FloodSpreader(FloodConfig config) {
        this.config = config;
        for (var tier : config.floodTiles) {
            tierByBlock.put(tier.block, tier);
        }
    }

    public boolean isInitialized() {
        return width > 0 && deadlines.length == width * height;
    }

    public void reset(int width, int height) {
        this.width = width;
        this.height = height;
        deadlines = new long[width * height];
        scheduled = new BitSet(width * height);
        seededPulses = new BitSet(width * height);
        heapSize = 0;
        pendingUpdates.clear();
        pendingCount = 0;
        sweepQueue.clear();
        reachable.clear();
        nextSweepAt = 0;
    }

    public int posOf(Tile tile) {
        return tile.x + tile.y * width;
    }

    public void onTileDestroyed(int pos) {
        scheduled.clear(pos);
        seededPulses.clear(pos);
        deadlines[pos] = 0;
    }

    /**
     * Ensures every tile on each core's perimeter ring has a pending event.
     * Idempotent and cheap; safe to call every tick.
     */
    public void seed(Seq<Building> cores, float multiplier) {
        long now = Time.millis();

        if (now >= nextSweepAt) {
            nextSweepAt = now + ORPHAN_SWEEP_MILLIS;
            sweepOrphans(cores);
        }

        var firstTier = config.floodTiles.get(0);

        for (var core : cores) {
            int size = core.block.size;
            int leftOffset = (size - 1) / 2;
            int rightOffset = size / 2;
            int cx = core.tile.x;
            int cy = core.tile.y;

            for (int y = cy - leftOffset; y <= cy + rightOffset; y++) {
                touch(Vars.world.tile(cx - leftOffset - 1, y), firstTier, multiplier, now);
                touch(Vars.world.tile(cx + rightOffset + 1, y), firstTier, multiplier, now);
            }
            for (int x = cx - leftOffset; x <= cx + rightOffset; x++) {
                touch(Vars.world.tile(x, cy - leftOffset - 1), firstTier, multiplier, now);
                touch(Vars.world.tile(x, cy + rightOffset + 1), firstTier, multiplier, now);
            }
        }
    }

    /**
     * Retires every scheduled tile that has no connection to an unsuppressed core
     * through the set of scheduled tiles. Cost is proportional to active flood
     * tiles, not map size.
     */
    private void sweepOrphans(Seq<Building> cores) {
        reachable.clear();
        sweepQueue.clear();

        for (var core : cores) {
            int size = core.block.size;
            int leftOffset = (size - 1) / 2;
            int rightOffset = size / 2;
            int cx = core.tile.x;
            int cy = core.tile.y;

            for (int y = cy - leftOffset; y <= cy + rightOffset; y++) {
                for (int x = cx - leftOffset; x <= cx + rightOffset; x++) {
                    sweepQueue.add(x + y * width);
                }
            }
        }

        int head = 0;
        while (head < sweepQueue.size) {
            int pos = sweepQueue.get(head++);
            int x = pos % width;
            int y = pos / width;

            visitSweepNeighbor(x - 1, y);
            visitSweepNeighbor(x + 1, y);
            visitSweepNeighbor(x, y - 1);
            visitSweepNeighbor(x, y + 1);
        }

        for (int pos = scheduled.nextSetBit(0); pos >= 0; pos = scheduled.nextSetBit(pos + 1)) {
            if (!reachable.get(pos)) {
                clear(pos);
            }
        }
    }

    private void visitSweepNeighbor(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }

        int pos = x + y * width;
        if (scheduled.get(pos) && !reachable.get(pos)) {
            reachable.set(pos);
            sweepQueue.add(pos);
        }
    }

    /** Processes all events due at or before the current time, then emits batched tile updates. */
    public void tick(float multiplier) {
        long now = Time.millis();

        while (heapSize > 0 && heapAt[0] <= now) {
            int pos = heapPos[0];
            pop();
            process(pos, now, multiplier);
        }

        flushUpdates();
    }

    private void touch(Tile tile, FloodConfig.FloodTile firstTier, float multiplier, long now) {
        if (tile == null) {
            return;
        }

        int pos = posOf(tile);
        if (scheduled.get(pos)) {
            return;
        }
        scheduled.set(pos);

        var build = tile.build;
        if (build != null && build.team == Team.crux) {
            push(now, pos);
        } else if (build == null && (tile.block() == Blocks.air || tile.block().alwaysReplace)) {
            place(tile, firstTier, now, multiplier);
            propagate(tile, now);
        } else if (build != null) {
            seededPulses.set(pos);
            push(now + DAMAGE_PULSE_MILLIS, pos);
        } else {
            scheduled.clear(pos);
        }
    }

    private void process(int pos, long now, float multiplier) {
        Tile tile = Vars.world.tile(pos % width, pos / width);
        if (tile == null) {
            clear(pos);
            return;
        }

        var build = tile.build;
        if (build == null || !build.isValid()) {
            clear(pos);
            return;
        }

        if (build.team != Team.crux) {
            if (!seededPulses.get(pos)) {
                clear(pos);
                return;
            }
            build.damage(config.floodTiles.get(0).damage * multiplier);
            push(now + DAMAGE_PULSE_MILLIS, pos);
            return;
        }

        var tier = tierByBlock.get(build.block);
        long deadline = deadlines[pos];

        if (tier != null && deadline > 0 && now >= deadline) {
            var next = config.nextTier(build);
            if (next != null) {
                place(tile, next, now, multiplier);
                propagate(tile, now);
                deadline = deadlines[pos];
                tier = next;
            } else {
                deadlines[pos] = 0;
                deadline = 0;
            }
        }

        boolean hasEnemy = tier != null && damageNeighbors(tile, tier.damage * multiplier);

        if (deadline > 0) {
            long pulseAt = now + DAMAGE_PULSE_MILLIS;
            push(hasEnemy && pulseAt < deadline ? pulseAt : deadline, pos);
        } else if (hasEnemy) {
            push(now + DAMAGE_PULSE_MILLIS, pos);
        } else {
            clear(pos);
        }
    }

    private void propagate(Tile tile, long now) {
        explore(tile.x - 1, tile.y, now);
        explore(tile.x + 1, tile.y, now);
        explore(tile.x, tile.y - 1, now);
        explore(tile.x, tile.y + 1, now);
    }

    private void explore(int x, int y, long now) {
        Tile neighbor = Vars.world.tile(x, y);
        if (neighbor == null) {
            return;
        }

        int pos = posOf(neighbor);
        if (scheduled.get(pos)) {
            return;
        }

        var build = neighbor.build;
        if (build != null && build.team == Team.crux) {
            scheduled.set(pos);
            push(now, pos);
        } else if (build == null && (neighbor.block() == Blocks.air || neighbor.block().alwaysReplace)) {
            scheduled.set(pos);
            deadlines[pos] = now + Mathf.random(1000 * 5, 1000 * 10);
            push(deadlines[pos], pos);
        }
    }

    private boolean damageNeighbors(Tile tile, float damage) {
        boolean any = damageAt(tile.x - 1, tile.y, damage);
        any |= damageAt(tile.x + 1, tile.y, damage);
        any |= damageAt(tile.x, tile.y - 1, damage);
        any |= damageAt(tile.x, tile.y + 1, damage);
        return any;
    }

    private boolean damageAt(int x, int y, float damage) {
        Tile neighbor = Vars.world.tile(x, y);
        if (neighbor == null) {
            return false;
        }
        var build = neighbor.build;
        if (build == null || !build.isValid() || build.team == Team.crux) {
            return false;
        }
        build.damage(damage);
        return true;
    }

    private void place(Tile tile, FloodConfig.FloodTile tier, long now, float multiplier) {
        int pos = posOf(tile);
        IntSeq seq = pendingUpdates.computeIfAbsent(tier.block, k -> new IntSeq());
        seq.add(pos);
        pendingCount++;

        deadlines[pos] = now + (long) (tier.evolveTime * 1000 / multiplier)
                + Mathf.random(1000 * 1, 1000 * 5);
    }

    private void clear(int pos) {
        scheduled.clear(pos);
        seededPulses.clear(pos);
        deadlines[pos] = 0;
    }

    private void flushUpdates() {
        if (pendingCount == 0) {
            return;
        }

        for (var entry : pendingUpdates.entrySet()) {
            IntSeq seq = entry.getValue();
            if (seq.isEmpty()) {
                continue;
            }
            int[] out = new int[seq.size];
            System.arraycopy(seq.items, 0, out, 0, seq.size);
            Call.setTileBlocks(entry.getKey(), Team.crux, out);
            seq.clear();
        }

        pendingUpdates.clear();
        pendingCount = 0;
    }

    private void push(long at, int pos) {
        if (heapSize == heapAt.length) {
            grow();
        }

        int i = heapSize++;
        heapAt[i] = at;
        heapPos[i] = pos;

        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (heapAt[parent] <= heapAt[i]) {
                break;
            }
            swap(parent, i);
            i = parent;
        }
    }

    private void pop() {
        heapSize--;
        heapAt[0] = heapAt[heapSize];
        heapPos[0] = heapPos[heapSize];

        int i = 0;
        while (true) {
            int left = 2 * i + 1;
            int right = left + 1;
            int smallest = i;

            if (left < heapSize && heapAt[left] < heapAt[smallest]) {
                smallest = left;
            }
            if (right < heapSize && heapAt[right] < heapAt[smallest]) {
                smallest = right;
            }
            if (smallest == i) {
                break;
            }
            swap(i, smallest);
            i = smallest;
        }
    }

    private void swap(int a, int b) {
        long tAt = heapAt[a];
        heapAt[a] = heapAt[b];
        heapAt[b] = tAt;

        int tPos = heapPos[a];
        heapPos[a] = heapPos[b];
        heapPos[b] = tPos;
    }

    private void grow() {
        int capacity = heapAt.length << 1;
        heapAt = Arrays.copyOf(heapAt, capacity);
        heapPos = Arrays.copyOf(heapPos, capacity);
    }
}
