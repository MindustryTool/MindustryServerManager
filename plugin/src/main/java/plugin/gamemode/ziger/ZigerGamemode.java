package plugin.gamemode.ziger;

import java.util.function.Consumer;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.BlockDestroyEvent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.type.Item;
import mindustry.type.Liquid;
import mindustry.world.Block;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItemFilter;
import mindustry.world.consumers.ConsumeItems;
import mindustry.world.consumers.ConsumeLiquid;
import mindustry.world.consumers.ConsumeLiquidFilter;
import mindustry.world.consumers.ConsumeLiquids;
import plugin.annotations.Gamemode;
import plugin.annotations.Listener;
import plugin.annotations.Trigger;

@Gamemode("ziger")
@RequiredArgsConstructor
public class ZigerGamemode {

    private final ZigerConfig config;

    private final ObjectMap<Integer, FillableBuilding> fillableBuildings = new ObjectMap<>();
    private final ObjectMap<Block, FillInfo> blockCache = new ObjectMap<>();

    private Seq<Item> allItems;
    private Seq<Liquid> allLiquids;

    @Listener
    private void onWorldLoad(WorldLoadEvent event) {
        clearCache();
        allItems = Vars.content.items();
        allLiquids = Vars.content.liquids();
        scanWorld();
    }

    @Listener
    private void onBlockPlaced(BlockBuildEndEvent event) {
        if (event.breaking) {
            fillableBuildings.remove(event.tile.pos());
        } else if (event.tile.build != null) {
            registerBuilding(event.tile.build);
        }
    }

    @Listener
    private void onBlockDestroyed(BlockDestroyEvent event) {
        fillableBuildings.remove(event.tile.pos());
    }

    @Listener
    private void onReset(ResetEvent event) {
        clearCache();
    }

    @Trigger(EventType.Trigger.update)
    private void tick() {
        for (var entry : fillableBuildings) {
            FillableBuilding fb = entry.value;
            fb.info.fill(fb.building);
        }
    }

    private void scanWorld() {
        Groups.build.each(this::registerBuilding);
    }

    private void registerBuilding(Building build) {
        if (build == null || !build.isValid()) {
            return;
        }

        int pos = build.tile.pos();

        if (fillableBuildings.containsKey(pos)) {
            return;
        }

        FillInfo info = cacheBlock(build.block);

        if (info == null) {
            return;
        }

        fillableBuildings.put(pos, new FillableBuilding(build, info));
    }

    private FillInfo cacheBlock(Block block) {
        FillInfo cached = blockCache.get(block);

        if (cached != null) {
            return cached;
        }

        FillInfo info = createFillInfo(block);

        if (info != null) {
            blockCache.put(block, info);
        }

        return info;
    }

    private FillInfo createFillInfo(Block block) {
        Seq<Consumer<Building>> handlers = new Seq<>();

        for (Consume consumer : block.consumers) {
            Consumer<Building> handler = createHandler(consumer, block);

            if (handler != null) {
                handlers.add(handler);
            }
        }

        if (handlers.size == 0) {
            return null;
        }

        return new FillInfo(handlers);
    }

    private Consumer<Building> createHandler(Consume consumer, Block block) {
        if (consumer instanceof ConsumeItems) {
            ConsumeItems ci = (ConsumeItems) consumer;

            if (ci.items == null || ci.items.length == 0) {
                return null;
            }

            if (block == Blocks.thoriumReactor) {
                return building -> {
                    if (building.items.get(Items.thorium) < config.thoriumMin) {
                        building.items.add(Items.thorium, config.thoriumTarget - building.items.get(Items.thorium));
                    }
                };
            }

            return building -> {
                for (var stack : ci.items) {
                    int current = building.items.get(stack.item);
                    int target = config.targetItems;
                    int missing = target - current;

                    if (missing > config.missingThreshold) {
                        building.items.add(stack.item, missing);
                    }
                }
            };
        } else if (consumer instanceof ConsumeLiquid) {
            ConsumeLiquid cl = (ConsumeLiquid) consumer;

            return building -> {
                float current = building.liquids.get(cl.liquid);
                float target = config.targetLiquids;
                float missing = target - current;

                if (missing > config.missingThreshold) {
                    building.liquids.add(cl.liquid, missing);
                }
            };
        } else if (consumer instanceof ConsumeLiquids) {
            ConsumeLiquids cl = (ConsumeLiquids) consumer;

            if (cl.liquids == null || cl.liquids.length == 0) {
                return null;
            }

            return building -> {
                for (var stack : cl.liquids) {
                    float current = building.liquids.get(stack.liquid);
                    float target = config.targetLiquids;
                    float missing = target - current;

                    if (missing > config.missingThreshold) {
                        building.liquids.add(stack.liquid, missing);
                    }
                }
            };
        } else if (consumer instanceof ConsumeItemFilter) {
            ConsumeItemFilter cif = (ConsumeItemFilter) consumer;

            return building -> {
                for (var item : allItems) {
                    if (cif.filter.get(item)) {
                        int current = building.items.get(item);
                        int target = config.targetItems;
                        int missing = target - current;

                        if (missing > config.missingThreshold) {
                            building.items.add(item, missing);
                        }
                    }
                }
            };
        } else if (consumer instanceof ConsumeLiquidFilter) {
            ConsumeLiquidFilter clf = (ConsumeLiquidFilter) consumer;

            return building -> {
                for (var liquid : allLiquids) {
                    if (clf.filter.get(liquid)) {
                        float current = building.liquids.get(liquid);
                        float target = config.targetLiquids;
                        float missing = target - current;

                        if (missing > config.missingThreshold) {
                            building.liquids.add(liquid, missing);
                        }
                    }
                }
            };
        }

        return null;
    }

    private void clearCache() {
        fillableBuildings.clear();
        blockCache.clear();
    }

    private static class FillInfo {
        final Seq<Consumer<Building>> handlers;

        FillInfo(Seq<Consumer<Building>> handlers) {
            this.handlers = handlers;
        }

        void fill(Building building) {
            for (Consumer<Building> handler : handlers) {
                handler.accept(building);
            }
        }
    }

    private static class FillableBuilding {
        final Building building;
        final FillInfo info;

        FillableBuilding(Building building, FillInfo info) {
            this.building = building;
            this.info = info;
        }
    }
}
