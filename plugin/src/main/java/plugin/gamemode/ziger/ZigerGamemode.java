package plugin.gamemode.ziger;

import java.util.function.Consumer;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
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
        Log.info("[accent]ZigerGamemode: onWorldLoad fired");
        clearCache();
        allItems = Vars.content.items();
        allLiquids = Vars.content.liquids();
        Log.info("[accent]ZigerGamemode: items=@, liquids=@", allItems.size, allLiquids.size);
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
        Log.info("[accent]ZigerGamemode: tick start, fillableBuildings size=@", fillableBuildings.size);
        for (var entry : fillableBuildings) {
            FillableBuilding fb = entry.value;
            Log.info("[accent]ZigerGamemode: filling building @ (@)", fb.building.pos(), fb.building.block);
            fb.info.fill(fb.building);
        }
    }

    private void scanWorld() {
        Log.info("[accent]ZigerGamemode: scanWorld, total builds=@", Groups.build.size());
        Groups.build.each(this::registerBuilding);
        Log.info("[accent]ZigerGamemode: scanWorld done, fillableBuildings size=@", fillableBuildings.size);
    }

    private void registerBuilding(Building build) {
        if (build == null || !build.isValid()) {
            Log.info("[accent]ZigerGamemode: registerBuilding skipped (null/invalid)");
            return;
        }

        int pos = build.tile.pos();

        if (fillableBuildings.containsKey(pos)) {
            return;
        }

        FillInfo info = cacheBlock(build.block);

        if (info == null) {
            Log.info("[accent]ZigerGamemode: registerBuilding no FillInfo for block @", build.block);
            return;
        }

        Log.info("[accent]ZigerGamemode: registered building @ (@) with @ handlers",
            pos, build.block, info.handlers.size);
        fillableBuildings.put(pos, new FillableBuilding(build, info));
    }

    private FillInfo cacheBlock(Block block) {
        FillInfo cached = blockCache.get(block);

        if (cached != null) {
            Log.info("[accent]ZigerGamemode: cacheBlock hit for @", block);
            return cached;
        }

        FillInfo info = createFillInfo(block);
        Log.info("[accent]ZigerGamemode: cacheBlock block=@, info=@", block, info);

        if (info != null) {
            blockCache.put(block, info);
        }

        return info;
    }

    private FillInfo createFillInfo(Block block) {
        Seq<Consumer<Building>> handlers = new Seq<>();
        Log.info("[accent]ZigerGamemode: createFillInfo block=@, consumers count=@", block, block.consumers.length);

        for (Consume consumer : block.consumers) {
            Consumer<Building> handler = createHandler(consumer, block);

            if (handler != null) {
                handlers.add(handler);
                Log.info("[accent]ZigerGamemode: handler created for consumer @ on @", consumer, block);
            } else {
                Log.info("[accent]ZigerGamemode: no handler for consumer @ on @", consumer, block);
            }
        }

        if (handlers.size == 0) {
            Log.info("[accent]ZigerGamemode: no handlers for block @, returning null", block);
            return null;
        }

        return new FillInfo(handlers);
    }

    private Consumer<Building> createHandler(Consume consumer, Block block) {
        if (consumer instanceof ConsumeItems) {
            Log.info("[accent]ZigerGamemode: createHandler ConsumeItems for @", block);
            ConsumeItems ci = (ConsumeItems) consumer;

            if (ci.items == null || ci.items.length == 0) {
                Log.info("[accent]ZigerGamemode: ConsumeItems items empty for @", block);
                return null;
            }

            if (block == Blocks.thoriumReactor) {
                Log.info("[accent]ZigerGamemode: thoriumReactor special handler for @", block);
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

                    if (missing > 0) {
                        building.items.add(stack.item, missing);
                    }
                }
            };
        } else if (consumer instanceof ConsumeLiquid) {
            Log.info("[accent]ZigerGamemode: createHandler ConsumeLiquid for @", block);
            ConsumeLiquid cl = (ConsumeLiquid) consumer;

            return building -> {
                float current = building.liquids.get(cl.liquid);
                float target = config.targetLiquids;
                float missing = target - current;

                if (missing > 0) {
                    building.liquids.add(cl.liquid, missing);
                }
            };
        } else if (consumer instanceof ConsumeLiquids) {
            Log.info("[accent]ZigerGamemode: createHandler ConsumeLiquids for @", block);
            ConsumeLiquids cl = (ConsumeLiquids) consumer;

            if (cl.liquids == null || cl.liquids.length == 0) {
                Log.info("[accent]ZigerGamemode: ConsumeLiquids liquids empty for @", block);
                return null;
            }

            return building -> {
                for (var stack : cl.liquids) {
                    float current = building.liquids.get(stack.liquid);
                    float target = config.targetLiquids;
                    float missing = target - current;

                    if (missing > 0) {
                        building.liquids.add(stack.liquid, missing);
                    }
                }
            };
        } else if (consumer instanceof ConsumeItemFilter) {
            Log.info("[accent]ZigerGamemode: createHandler ConsumeItemFilter for @", block);
            ConsumeItemFilter cif = (ConsumeItemFilter) consumer;

            return building -> {
                for (var item : allItems) {
                    if (cif.filter.get(item)) {
                        int current = building.items.get(item);
                        int target = config.targetItems;
                        int missing = target - current;

                        if (missing > 0) {
                            building.items.add(item, missing);
                        }
                    }
                }
            };
        } else if (consumer instanceof ConsumeLiquidFilter) {
            Log.info("[accent]ZigerGamemode: createHandler ConsumeLiquidFilter for @", block);
            ConsumeLiquidFilter clf = (ConsumeLiquidFilter) consumer;

            return building -> {
                for (var liquid : allLiquids) {
                    if (clf.filter.get(liquid)) {
                        float current = building.liquids.get(liquid);
                        float target = config.targetLiquids;
                        float missing = target - current;

                        if (missing > 0) {
                            building.liquids.add(liquid, missing);
                        }
                    }
                }
            };
        }

        Log.info("[accent]ZigerGamemode: createHandler unhandled consumer @ for @", consumer.getClass().getName(), block);
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
