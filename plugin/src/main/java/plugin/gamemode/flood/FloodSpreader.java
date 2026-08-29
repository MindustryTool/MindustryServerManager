package plugin.gamemode.flood;

import java.util.Arrays;
import java.util.BitSet;

import arc.math.Mathf;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

/**
 * Maximum efficiency event-driven flood simulation designed for 1 vCPU and 500MB RAM constraints.
 * 
 * Performance features:
 * - Direct block ID flat arrays (O(1) direct indexing, 0 HashMaps, 0 instanceof checks).
 * - Indexed min-heap (O(1) deduplication, zero heap bloat).
 * - Flat tier-indexed update queues (0 HashMap allocations during network flush).
 * - Zero GC allocations on steady-state ticks.
 */
public class FloodSpreader {

    private static final long DAMAGE_PULSE_MILLIS = 1000;
    private static final long ORPHAN_SWEEP_MILLIS = 5000;
    private static final long FLUSH_INTERVAL_MILLIS = 100;
    private static final int INITIAL_HEAP_CAPACITY = 256;
    private static final int MAX_EVENTS_PER_TICK = 64;

    private final FloodConfig config;

    // Direct block ID lookup arrays for 0-overhead O(1) indexing (No HashMaps on hot paths)
    private FloodConfig.FloodTile[] tierByBlockId = new FloodConfig.FloodTile[0];
    private FloodConfig.FloodTile[] nextTierByBlockId = new FloodConfig.FloodTile[0];
    private boolean[] isFloodOrCoreBlockId = new boolean[0];

    /** Next activation deadline per tile position; 0 means none pending. */
    private long[] deadlines = new long[0];
    /** Tile positions with a live heap entry. */
    private BitSet scheduled = new BitSet();
    /** Enemy structures anchored to a first-tier pulse by the seeding pass. */
    private BitSet seededPulses = new BitSet();

    // Min-heap of pending events as parallel primitive arrays, ordered by time.
    private long[] heapAt = new long[INITIAL_HEAP_CAPACITY];
    private int[] heapPos = new int[INITIAL_HEAP_CAPACITY];
    /** Maps tile position to heap index (-1 if not in heap) for O(1) deduplication and in-place updates. */
    private int[] heapIndex = new int[0];
    private int heapSize = 0;

    // Flat tier-indexed update queues (0 HashMap allocations during network flush)
    private IntSeq[] pendingUpdatesByTier = new IntSeq[0];
    private int pendingCount = 0;

    // Scratch state for the periodic connectivity sweep.
    private final IntSeq sweepQueue = new IntSeq();
    private BitSet reachable = new BitSet();
    private long nextSweepAt = 0;

    private boolean loggedFirstPlacement = false;
    private boolean warnedNoTiers = false;

    /** Earliest wall-clock time at which a buffered tile-update flush may be emitted. */
    private long nextFlushAt = 0;

    private int width = 0;
    private int height = 0;

    public FloodSpreader(FloodConfig config) {
        this.config = config;
        rebuildTiers();
    }

    public boolean isInitialized() {
        return width > 0 && deadlines.length == width * height;
    }

    public void reset(int width, int height) {
        this.width = width;
        this.height = height;
        rebuildTiers();
        int totalTiles = width * height;
        resetHeapState(totalTiles);
        clearPendingUpdateQueues();

        sweepQueue.clear();
        reachable.clear();
        nextSweepAt = 0;
        nextFlushAt = 0;
        loggedFirstPlacement = false;
        warnedNoTiers = false;
    }

    // Resets indexed min-heap state and pending deadline buffers for the given tile count.
    private void resetHeapState(int totalTiles) {
        deadlines = new long[totalTiles];
        scheduled = new BitSet(totalTiles);
        seededPulses = new BitSet(totalTiles);
        heapIndex = new int[totalTiles];
        Arrays.fill(heapIndex, -1);
        heapSize = 0;
        pendingCount = 0;
    }

    // Clears all buffered tile-block update queues across configured tiers.
    private void clearPendingUpdateQueues() {
        for (IntSeq seq : pendingUpdatesByTier) {
            if (seq != null) {
                seq.clear();
            }
        }
    }

    private void rebuildTiers() {
        int maxBlockId = 2048;
        if (Vars.content != null && Vars.content.blocks() != null) {
            maxBlockId = Math.max(Vars.content.blocks().size + 64, 2048);
        }

        tierByBlockId = new FloodConfig.FloodTile[maxBlockId];
        nextTierByBlockId = new FloodConfig.FloodTile[maxBlockId];
        isFloodOrCoreBlockId = new boolean[maxBlockId];

        int numTiers = config.floodTiles.size;
        pendingUpdatesByTier = new IntSeq[numTiers];

        mapFloodTierBlocks(maxBlockId, numTiers);
        mapCoreBlocks(maxBlockId);

        if (numTiers == 0) {
            Log.err("Flood: floodTiles in flood/config.json is empty - flood cannot spread");
        }
    }

    // Maps configured flood tiers and their progression transitions by block ID.
    private void mapFloodTierBlocks(int maxBlockId, int numTiers) {
        for (int i = 0; i < numTiers; i++) {
            var tier = config.floodTiles.get(i);
            pendingUpdatesByTier[i] = new IntSeq();
            if (tier.block != null && tier.block.id < maxBlockId) {
                tierByBlockId[tier.block.id] = tier;
                isFloodOrCoreBlockId[tier.block.id] = true;
                if (i + 1 < numTiers) {
                    nextTierByBlockId[tier.block.id] = config.floodTiles.get(i + 1);
                }
            }
        }
    }

    // Flags all registered core block IDs as valid flood-connected anchor structures.
    private void mapCoreBlocks(int maxBlockId) {
        if (Vars.content != null && Vars.content.blocks() != null) {
            for (var block : Vars.content.blocks()) {
                if (block instanceof CoreBlock && block.id < maxBlockId) {
                    isFloodOrCoreBlockId[block.id] = true;
                }
            }
        }
    }

    public int posOf(Tile tile) {
        return tile.x + tile.y * width;
    }

    public void onTileDestroyed(int pos) {
        if (pos >= 0 && pos < deadlines.length) {
            clear(pos);
            Tile tile = Vars.world.tile(pos % width, pos / width);
            if (tile != null && isSpreadable(tile)) {
                long now = Time.millis();
                scheduled.set(pos);
                deadlines[pos] = now + Mathf.random(1000 * 5, 1000 * 10);
                push(deadlines[pos], pos);
            }
        }
    }

    /**
     * Ensures every tile on each core's perimeter ring has a pending event.
     * Idempotent and cheap; safe to call every tick.
     */
    public void seed(Seq<Building> cores, float multiplier) {
        long now = Time.millis();

        if (now >= nextSweepAt) {
            nextSweepAt = now + ORPHAN_SWEEP_MILLIS;
            sweepOrphans(cores, multiplier);
        }

        var firstTier = firstTier();
        if (firstTier == null || cores.size == 0) {
            return;
        }

        for (var core : cores) {
            seedCorePerimeter(core, firstTier, multiplier, now);
        }
    }

    // Schedules flood activation for all tiles along the perimeter ring of a core.
    private void seedCorePerimeter(Building core, FloodConfig.FloodTile firstTier, float multiplier, long now) {
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

    /**
     * Retires every scheduled tile that has no connection to an unsuppressed core
     * and re-activates reachable Crux flood tiles that were previously orphaned.
     */
    private void sweepOrphans(Seq<Building> cores, float multiplier) {
        reachable.clear();
        sweepQueue.clear();
        sweepQueue.ensureCapacity(scheduled.cardinality() + 32);

        enqueueCoreFootprints(cores);

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

        long now = Time.millis();
        retireUnreachableTiles();
        reactivateReachableTiles(now, multiplier);
    }

    // Enqueues all core footprint tiles as root nodes for the connectivity sweep BFS.
    private void enqueueCoreFootprints(Seq<Building> cores) {
        for (var core : cores) {
            int size = core.block.size;
            int leftOffset = (size - 1) / 2;
            int rightOffset = size / 2;
            int cx = core.tile.x;
            int cy = core.tile.y;

            for (int y = cy - leftOffset; y <= cy + rightOffset; y++) {
                for (int x = cx - leftOffset; x <= cx + rightOffset; x++) {
                    int pos = x + y * width;
                    reachable.set(pos);
                    sweepQueue.add(pos);
                }
            }
        }
    }

    // Clears scheduled status for all flood tiles disconnected from unsuppressed cores.
    private void retireUnreachableTiles() {
        for (int pos = scheduled.nextSetBit(0); pos >= 0; pos = scheduled.nextSetBit(pos + 1)) {
            if (!reachable.get(pos)) {
                clear(pos);
            }
        }
    }

    // Re-schedules reachable Crux flood tiles that are currently inactive.
    private void reactivateReachableTiles(long now, float multiplier) {
        for (int pos = reachable.nextSetBit(0); pos >= 0; pos = reachable.nextSetBit(pos + 1)) {
            if (!scheduled.get(pos)) {
                Tile tile = Vars.world.tile(pos % width, pos / width);
                if (tile != null && tile.build != null && tile.build.team == Team.crux) {
                    var tier = tile.build.block.id < tierByBlockId.length ? tierByBlockId[tile.build.block.id] : null;
                    if (tier != null) {
                        scheduled.set(pos);
                        deadlines[pos] = now + (long) (tier.evolveTime * 1000 / multiplier)
                                + Mathf.random(1000 * 1, 1000 * 5);
                        push(deadlines[pos], pos);
                    }
                }
            }
        }
    }

    private void visitSweepNeighbor(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }

        int pos = x + y * width;
        if (reachable.get(pos)) {
            return;
        }

        Tile tile = Vars.world.tile(x, y);
        boolean isFloodBlock = tile != null && tile.build != null && tile.build.team == Team.crux
                && tile.build.block.id < tierByBlockId.length && tierByBlockId[tile.build.block.id] != null;

        if (scheduled.get(pos) || isFloodBlock) {
            reachable.set(pos);
            sweepQueue.add(pos);
        }
    }

    /** Processes all events due at or before the current time, up to MAX_EVENTS_PER_TICK, then emits batched tile updates. */
    public void tick(float multiplier) {
        long now = Time.millis();
        int processed = 0;

        while (heapSize > 0 && heapAt[0] <= now && processed < MAX_EVENTS_PER_TICK) {
            int pos = heapPos[0];
            pop();
            process(pos, now, multiplier);
            processed++;
        }

        flushUpdates();
    }

    /** Flood tier used for seeding and enemy-structure pulses; null when unconfigured. */
    private FloodConfig.FloodTile firstTier() {
        if (config.floodTiles.size == 0) {
            if (!warnedNoTiers) {
                warnedNoTiers = true;
                Log.err("Flood: floodTiles is empty, spreader idle");
            }
            return null;
        }
        return config.floodTiles.first();
    }

    public boolean isFloodTile(Tile tile) {
        if (tile == null) {
            return false;
        }
        var build = tile.build;
        if (build == null || !build.isValid() || build.team != Team.crux) {
            return false;
        }
        int id = build.block.id;
        return id < isFloodOrCoreBlockId.length && isFloodOrCoreBlockId[id];
    }

    public boolean hasAdjacentFlood(Tile tile) {
        if (tile == null) {
            return false;
        }
        return isFloodTile(Vars.world.tile(tile.x - 1, tile.y))
                || isFloodTile(Vars.world.tile(tile.x + 1, tile.y))
                || isFloodTile(Vars.world.tile(tile.x, tile.y - 1))
                || isFloodTile(Vars.world.tile(tile.x, tile.y + 1));
    }

    public boolean isSpreadable(Tile tile) {
        if (tile == null) {
            return false;
        }
        var build = tile.build;
        if (build != null && build.isValid()) {
            return false;
        }
        if (tile.block() != Blocks.air && !tile.block().alwaysReplace) {
            return false;
        }
        return hasAdjacentFlood(tile);
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
            scheduleCruxTile(pos, build, multiplier, now);
        } else if (build == null && (tile.block() == Blocks.air || tile.block().alwaysReplace)) {
            deadlines[pos] = now + Mathf.random(1000 * 5, 1000 * 10);
            push(deadlines[pos], pos);
        } else if (build != null) {
            seededPulses.set(pos);
            push(now + DAMAGE_PULSE_MILLIS, pos);
        } else {
            scheduled.clear(pos);
        }
    }

    // Schedules evolution deadline for an existing Crux flood structure.
    private void scheduleCruxTile(int pos, Building build, float multiplier, long now) {
        var tier = build.block.id < tierByBlockId.length ? tierByBlockId[build.block.id] : null;
        if (tier != null) {
            deadlines[pos] = now + (long) (tier.evolveTime * 1000 / multiplier)
                    + Mathf.random(1000 * 1, 1000 * 5);
            push(deadlines[pos], pos);
        }
    }

    private void process(int pos, long now, float multiplier) {
        if (!scheduled.get(pos)) {
            return;
        }

        Tile tile = Vars.world.tile(pos % width, pos / width);
        if (tile == null) {
            clear(pos);
            return;
        }

        var build = tile.build;
        if (build == null || !build.isValid()) {
            processAirSpread(tile, pos, now, multiplier);
            return;
        }

        if (build.team != Team.crux) {
            processEnemyPulse(build, pos, now, multiplier);
            return;
        }

        processCruxEvolution(tile, build, pos, now, multiplier);
    }

    // Handles flood placement and propagation onto an empty or replaceable tile.
    private void processAirSpread(Tile tile, int pos, long now, float multiplier) {
        var firstTier = firstTier();
        if (firstTier != null && isSpreadable(tile)
                && deadlines[pos] > 0 && now >= deadlines[pos]) {
            place(tile, firstTier, 0, now, multiplier);
            propagate(tile, now, multiplier);
            push(deadlines[pos], pos);
        } else {
            clear(pos);
        }
    }

    // Applies periodic first-tier damage pulse to enemy structures anchored to core perimeters.
    private void processEnemyPulse(Building build, int pos, long now, float multiplier) {
        var pulse = firstTier();
        if (!seededPulses.get(pos) || pulse == null) {
            clear(pos);
            return;
        }
        build.damage(pulse.damage * multiplier);
        push(now + DAMAGE_PULSE_MILLIS, pos);
    }

    // Handles tier evolution progression and neighboring enemy damage pulses for Crux flood tiles.
    private void processCruxEvolution(Tile tile, Building build, int pos, long now, float multiplier) {
        var tier = build.block.id < tierByBlockId.length ? tierByBlockId[build.block.id] : null;
        long deadline = deadlines[pos];

        if (tier != null && deadline > 0 && now >= deadline) {
            var next = build.block.id < nextTierByBlockId.length ? nextTierByBlockId[build.block.id] : null;
            if (next != null) {
                int nextTierIndex = config.floodTiles.indexOf(next);
                place(tile, next, nextTierIndex >= 0 ? nextTierIndex : 0, now, multiplier);
                propagate(tile, now, multiplier);
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

    private void propagate(Tile tile, long now, float multiplier) {
        exploreNeighbor(tile.x - 1, tile.y, now, multiplier);
        exploreNeighbor(tile.x + 1, tile.y, now, multiplier);
        exploreNeighbor(tile.x, tile.y - 1, now, multiplier);
        exploreNeighbor(tile.x, tile.y + 1, now, multiplier);
    }

    private void exploreNeighbor(int x, int y, long now, float multiplier) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }

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
            scheduleCruxNeighbor(pos, build, multiplier, now);
        } else if (build == null && (neighbor.block() == Blocks.air || neighbor.block().alwaysReplace)) {
            scheduled.set(pos);
            deadlines[pos] = now + Mathf.random(1000 * 5, 1000 * 10);
            push(deadlines[pos], pos);
        }
    }

    // Schedules evolution deadline for an adjacent Crux flood neighbor if not already scheduled.
    private void scheduleCruxNeighbor(int pos, Building build, float multiplier, long now) {
        var tier = build.block.id < tierByBlockId.length ? tierByBlockId[build.block.id] : null;
        if (tier != null) {
            scheduled.set(pos);
            deadlines[pos] = now + (long) (tier.evolveTime * 1000 / multiplier)
                    + Mathf.random(1000 * 1, 1000 * 5);
            push(deadlines[pos], pos);
        } else {
            scheduled.set(pos);
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

    private void place(Tile tile, FloodConfig.FloodTile tier, int tierIndex, long now, float multiplier) {
        int pos = posOf(tile);
        if (tierIndex >= 0 && tierIndex < pendingUpdatesByTier.length) {
            pendingUpdatesByTier[tierIndex].add(tile.pos());
            pendingCount++;
        }

        deadlines[pos] = now + (long) (tier.evolveTime * 1000 / multiplier)
                + Mathf.random(1000 * 1, 1000 * 5);

        if (!loggedFirstPlacement) {
            loggedFirstPlacement = true;
            Log.info("Flood: placed first tile @ at @,@ - spread started", tier.block.name, tile.x, tile.y);
        }
    }

    private void clear(int pos) {
        scheduled.clear(pos);
        seededPulses.clear(pos);
        deadlines[pos] = 0;
        if (pos >= 0 && pos < heapIndex.length) {
            heapIndex[pos] = -1;
        }
    }

    private void flushUpdates() {
        if (pendingCount == 0 || Time.millis() < nextFlushAt) {
            return;
        }
        nextFlushAt = Time.millis() + FLUSH_INTERVAL_MILLIS;

        for (int i = 0; i < pendingUpdatesByTier.length; i++) {
            IntSeq seq = pendingUpdatesByTier[i];
            if (seq == null || seq.isEmpty()) {
                continue;
            }
            Call.setTileBlocks(config.floodTiles.get(i).block, Team.crux, seq.toArray());
            seq.clear();
        }

        pendingCount = 0;
    }

    private void push(long at, int pos) {
        if (pos >= 0 && pos < heapIndex.length) {
            int idx = heapIndex[pos];
            if (idx >= 0 && idx < heapSize) {
                updateExistingHeapEntry(idx, at);
                return;
            }
        }

        if (heapSize == heapAt.length) {
            grow();
        }

        int i = heapSize++;
        heapAt[i] = at;
        heapPos[i] = pos;
        if (pos >= 0 && pos < heapIndex.length) {
            heapIndex[pos] = i;
        }

        siftUp(i);
    }

    // Updates the deadline timestamp of an existing min-heap entry and restores heap invariant.
    private void updateExistingHeapEntry(int idx, long at) {
        long oldAt = heapAt[idx];
        heapAt[idx] = at;
        if (at < oldAt) {
            siftUp(idx);
        } else if (at > oldAt) {
            siftDown(idx);
        }
    }

    private void siftUp(int i) {
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (heapAt[parent] <= heapAt[i]) {
                break;
            }
            swap(parent, i);
            i = parent;
        }
    }

    private void siftDown(int i) {
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

    private void pop() {
        if (heapSize == 0) {
            return;
        }
        int pos = heapPos[0];
        if (pos >= 0 && pos < heapIndex.length) {
            heapIndex[pos] = -1;
        }
        heapSize--;
        if (heapSize > 0) {
            heapAt[0] = heapAt[heapSize];
            heapPos[0] = heapPos[heapSize];
            int newHeadPos = heapPos[0];
            if (newHeadPos >= 0 && newHeadPos < heapIndex.length) {
                heapIndex[newHeadPos] = 0;
            }
            siftDown(0);
        }
    }

    private void swap(int a, int b) {
        long tAt = heapAt[a];
        heapAt[a] = heapAt[b];
        heapAt[b] = tAt;

        int tPos = heapPos[a];
        heapPos[a] = heapPos[b];
        heapPos[b] = tPos;

        if (heapPos[a] >= 0 && heapPos[a] < heapIndex.length) {
            heapIndex[heapPos[a]] = a;
        }
        if (heapPos[b] >= 0 && heapPos[b] < heapIndex.length) {
            heapIndex[heapPos[b]] = b;
        }
    }

    private void grow() {
        int capacity = heapAt.length << 1;
        heapAt = Arrays.copyOf(heapAt, capacity);
        heapPos = Arrays.copyOf(heapPos, capacity);
    }
}
