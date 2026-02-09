package plugin.gamemode.catali;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import arc.struct.Seq;
import mindustry.content.StatusEffects;
import mindustry.gen.Unit;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import lombok.Data;
import lombok.NoArgsConstructor;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.world.Block;
import plugin.annotations.Configuration;
import plugin.json.BlockDeserializer;
import plugin.json.DurationDeserializer;
import plugin.json.DurationSerializer;
import plugin.json.MappableContentSerializer;
import plugin.json.UnitTypeDeserializer;

import static mindustry.content.UnitTypes.*;
import static mindustry.content.Blocks.*;

@Configuration("catali.json")
@NoArgsConstructor
@Data
public class CataliConfig {

    @JsonSerialize(using = DurationSerializer.class)
    @JsonDeserialize(using = DurationDeserializer.class)
    public Duration enemyUnitSpawnTime = Duration.ofSeconds(1);

    @JsonSerialize(using = DurationSerializer.class)
    @JsonDeserialize(using = DurationDeserializer.class)
    public Duration enemyBlockSpawnTime = Duration.ofSeconds(2);

    public List<UnitRespawnTimeEntry> unitRespawnTime = _getUnitRespawnTime();
    public List<UnitExpEntry> unitExp = _getUnitExp();
    public List<BlockExpEntry> blockExp = _getBlockExp();
    public List<BlockSpawnChanceEntry> blockSpawnChance = _getBlockSpawnChance();
    public List<UnitUpgradeEntry> unitUpgrade = _getUnitUpgrade();
    public List<UnitSpawnChance> unitSpawnChance = _getUnitSpawnChance();

    public int unitStartSpawnLevel = 15;

    public Set<UnitType> getUnitEvolutions(UnitType type) {
        for (var entry : unitUpgrade) {
            if (entry.unit == type) {
                return entry.upgrades;
            }
        }
        return new HashSet<>();
    }

    public static Seq<Unit> selectUnitsCanBeBuff(Seq<Unit> units) {
        return units;
    }

    public static Seq<StatusEffect> selectBuffsCanBeApplied(Unit unit) {
        return Seq.with(StatusEffects.overdrive, StatusEffects.boss, StatusEffects.shielded);
    }

    private List<UnitExpEntry> _getUnitExp() {
        List<UnitExpEntry> unitExp = new ArrayList<>();
        unitExp.add(new UnitExpEntry(dagger, 30));
        unitExp.add(new UnitExpEntry(nova, 24));
        unitExp.add(new UnitExpEntry(flare, 14));
        unitExp.add(new UnitExpEntry(poly, 80));
        unitExp.add(new UnitExpEntry(mace, 110));
        unitExp.add(new UnitExpEntry(pulsar, 64));
        unitExp.add(new UnitExpEntry(horizon, 68));
        unitExp.add(new UnitExpEntry(mega, 92));
        unitExp.add(new UnitExpEntry(risso, 60));
        unitExp.add(new UnitExpEntry(retusa, 54));
        unitExp.add(new UnitExpEntry(stell, 170));
        unitExp.add(new UnitExpEntry(merui, 136));
        unitExp.add(new UnitExpEntry(elude, 120));
        unitExp.add(new UnitExpEntry(fortress, 180));
        unitExp.add(new UnitExpEntry(quasar, 128));
        unitExp.add(new UnitExpEntry(zenith, 140));
        unitExp.add(new UnitExpEntry(quad, 1200));
        unitExp.add(new UnitExpEntry(minke, 120));
        unitExp.add(new UnitExpEntry(oxynoe, 112));
        unitExp.add(new UnitExpEntry(locus, 420));
        unitExp.add(new UnitExpEntry(cleroi, 220));
        unitExp.add(new UnitExpEntry(avert, 220));
        unitExp.add(new UnitExpEntry(scepter, 1800));
        unitExp.add(new UnitExpEntry(vela, 1640));
        unitExp.add(new UnitExpEntry(antumbra, 1440));
        unitExp.add(new UnitExpEntry(oct, 3000));
        unitExp.add(new UnitExpEntry(bryde, 182));
        unitExp.add(new UnitExpEntry(cyerce, 174));
        unitExp.add(new UnitExpEntry(precept, 1000));
        unitExp.add(new UnitExpEntry(anthicus, 580));
        unitExp.add(new UnitExpEntry(obviate, 460));
        unitExp.add(new UnitExpEntry(reign, 4000));
        unitExp.add(new UnitExpEntry(corvus, 3000));
        unitExp.add(new UnitExpEntry(eclipse, 4000));
        unitExp.add(new UnitExpEntry(sei, 2000));
        unitExp.add(new UnitExpEntry(aegires, 2200));
        unitExp.add(new UnitExpEntry(vanquish, 2200));
        unitExp.add(new UnitExpEntry(tecta, 1460));
        unitExp.add(new UnitExpEntry(quell, 1200));
        unitExp.add(new UnitExpEntry(omura, 4400));
        unitExp.add(new UnitExpEntry(navanax, 4000));
        unitExp.add(new UnitExpEntry(conquer, 4400));
        unitExp.add(new UnitExpEntry(collaris, 3600));
        unitExp.add(new UnitExpEntry(disrupt, 2400));
        return unitExp;
    }

    private List<UnitRespawnTimeEntry> _getUnitRespawnTime() {
        List<UnitRespawnTimeEntry> unitRespawnTime = new ArrayList<>();

        unitRespawnTime.add(new UnitRespawnTimeEntry(dagger, Duration.ofSeconds(60)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(nova, Duration.ofSeconds(60)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(crawler, Duration.ofSeconds(60)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(flare, Duration.ofSeconds(60)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(poly, Duration.ofSeconds(30)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(risso, Duration.ofSeconds(80)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(retusa, Duration.ofSeconds(80)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(stell, Duration.ofSeconds(80)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(merui, Duration.ofSeconds(80)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(mace, Duration.ofSeconds(100)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(pulsar, Duration.ofSeconds(100)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(atrax, Duration.ofSeconds(100)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(horizon, Duration.ofSeconds(100)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(minke, Duration.ofSeconds(120)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(oxynoe, Duration.ofSeconds(120)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(locus, Duration.ofSeconds(120)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(cleroi, Duration.ofSeconds(120)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(avert, Duration.ofSeconds(100)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(fortress, Duration.ofSeconds(140)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(quasar, Duration.ofSeconds(140)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(spiroct, Duration.ofSeconds(140)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(zenith, Duration.ofSeconds(140)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(mega, Duration.ofSeconds(60)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(bryde, Duration.ofSeconds(180)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(cyerce, Duration.ofSeconds(180)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(precept, Duration.ofSeconds(180)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(anthicus, Duration.ofSeconds(180)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(obviate, Duration.ofSeconds(140)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(scepter, Duration.ofSeconds(200)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(vela, Duration.ofSeconds(200)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(arkyid, Duration.ofSeconds(200)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(antumbra, Duration.ofSeconds(200)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(quad, Duration.ofSeconds(160)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(sei, Duration.ofSeconds(240)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(aegires, Duration.ofSeconds(240)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(vanquish, Duration.ofSeconds(240)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(tecta, Duration.ofSeconds(240)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(quell, Duration.ofSeconds(180)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(reign, Duration.ofSeconds(240)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(corvus, Duration.ofSeconds(240)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(toxopid, Duration.ofSeconds(240)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(eclipse, Duration.ofSeconds(240)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(oct, Duration.ofSeconds(160)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(omura, Duration.ofSeconds(300)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(navanax, Duration.ofSeconds(300)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(conquer, Duration.ofSeconds(300)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(collaris, Duration.ofSeconds(300)));
        unitRespawnTime.add(new UnitRespawnTimeEntry(disrupt, Duration.ofSeconds(220)));

        return unitRespawnTime;
    }

    private List<BlockExpEntry> _getBlockExp() {
        List<BlockExpEntry> blockExp = new ArrayList<>();
        blockExp.add(new BlockExpEntry(copperWall, 32));
        blockExp.add(new BlockExpEntry(copperWallLarge, 128));
        blockExp.add(new BlockExpEntry(titaniumWall, 44));
        blockExp.add(new BlockExpEntry(titaniumWallLarge, 176));
        blockExp.add(new BlockExpEntry(berylliumWall, 52));
        blockExp.add(new BlockExpEntry(berylliumWallLarge, 208));
        blockExp.add(new BlockExpEntry(plastaniumWall, 52));
        blockExp.add(new BlockExpEntry(plastaniumWallLarge, 208));
        blockExp.add(new BlockExpEntry(tungstenWall, 72));
        blockExp.add(new BlockExpEntry(tungstenWallLarge, 288));
        blockExp.add(new BlockExpEntry(thoriumWall, 80));
        blockExp.add(new BlockExpEntry(thoriumWallLarge, 320));
        blockExp.add(new BlockExpEntry(phaseWall, 60));
        blockExp.add(new BlockExpEntry(phaseWallLarge, 240));
        blockExp.add(new BlockExpEntry(surgeWall, 92));
        blockExp.add(new BlockExpEntry(surgeWallLarge, 368));
        blockExp.add(new BlockExpEntry(carbideWall, 108));
        blockExp.add(new BlockExpEntry(carbideWallLarge, 432));
        blockExp.add(new BlockExpEntry(reinforcedSurgeWall, 100));
        blockExp.add(new BlockExpEntry(reinforcedSurgeWallLarge, 400));
        blockExp.add(new BlockExpEntry(container, 75));
        blockExp.add(new BlockExpEntry(vault, 225));
        blockExp.add(new BlockExpEntry(reinforcedContainer, 250));
        blockExp.add(new BlockExpEntry(reinforcedVault, 750));

        return blockExp;
    }

    private List<BlockSpawnChanceEntry> _getBlockSpawnChance() {
        List<BlockSpawnChanceEntry> blockSpawnChance = new ArrayList<>();

        blockSpawnChance.add(new BlockSpawnChanceEntry(copperWall, 1.0));
        blockSpawnChance.add(new BlockSpawnChanceEntry(copperWallLarge, 0.5));
        blockSpawnChance.add(new BlockSpawnChanceEntry(titaniumWall, 0.5));
        blockSpawnChance.add(new BlockSpawnChanceEntry(titaniumWallLarge, 0.02));
        blockSpawnChance.add(new BlockSpawnChanceEntry(berylliumWall, 0.067));
        blockSpawnChance.add(new BlockSpawnChanceEntry(berylliumWallLarge, 0.013));
        blockSpawnChance.add(new BlockSpawnChanceEntry(plastaniumWall, 0.057));
        blockSpawnChance.add(new BlockSpawnChanceEntry(plastaniumWallLarge, 0.011));
        blockSpawnChance.add(new BlockSpawnChanceEntry(tungstenWall, 0.05));
        blockSpawnChance.add(new BlockSpawnChanceEntry(tungstenWallLarge, 0.01));
        blockSpawnChance.add(new BlockSpawnChanceEntry(thoriumWall, 0.05));
        blockSpawnChance.add(new BlockSpawnChanceEntry(thoriumWallLarge, 0.009));
        blockSpawnChance.add(new BlockSpawnChanceEntry(phaseWall, 0.04));
        blockSpawnChance.add(new BlockSpawnChanceEntry(phaseWallLarge, 0.008));
        blockSpawnChance.add(new BlockSpawnChanceEntry(surgeWall, 0.025));
        blockSpawnChance.add(new BlockSpawnChanceEntry(surgeWallLarge, 0.005));
        blockSpawnChance.add(new BlockSpawnChanceEntry(carbideWall, 0.02));
        blockSpawnChance.add(new BlockSpawnChanceEntry(carbideWallLarge, 0.004));
        blockSpawnChance.add(new BlockSpawnChanceEntry(reinforcedSurgeWall, 0.017));
        blockSpawnChance.add(new BlockSpawnChanceEntry(reinforcedSurgeWallLarge, 0.003));
        blockSpawnChance.add(new BlockSpawnChanceEntry(container, 0.01));
        blockSpawnChance.add(new BlockSpawnChanceEntry(vault, 0.004));
        blockSpawnChance.add(new BlockSpawnChanceEntry(reinforcedContainer, 0.004));
        blockSpawnChance.add(new BlockSpawnChanceEntry(reinforcedVault, 0.001));
        blockSpawnChance.add(new BlockSpawnChanceEntry(thoriumReactor, 0.001));

        return blockSpawnChance;
    }

    private List<UnitUpgradeEntry> _getUnitUpgrade() {
        List<UnitUpgradeEntry> unitUpgrade = new ArrayList<>();

        unitUpgrade.add(new UnitUpgradeEntry(poly, set(dagger, flare, retusa, nova, mega)));
        unitUpgrade.add(new UnitUpgradeEntry(dagger, set(mace, atrax, stell)));
        unitUpgrade.add(new UnitUpgradeEntry(mace, set(fortress)));
        unitUpgrade.add(new UnitUpgradeEntry(fortress, set(scepter)));
        unitUpgrade.add(new UnitUpgradeEntry(scepter, set(reign)));
        unitUpgrade.add(new UnitUpgradeEntry(atrax, set(spiroct)));
        unitUpgrade.add(new UnitUpgradeEntry(spiroct, set(arkyid)));
        unitUpgrade.add(new UnitUpgradeEntry(arkyid, set(toxopid)));
        unitUpgrade.add(new UnitUpgradeEntry(stell, set(locus)));
        unitUpgrade.add(new UnitUpgradeEntry(locus, set(precept)));
        unitUpgrade.add(new UnitUpgradeEntry(precept, set(vanquish)));
        unitUpgrade.add(new UnitUpgradeEntry(vanquish, set(conquer)));
        unitUpgrade.add(new UnitUpgradeEntry(flare, set(horizon, elude)));
        unitUpgrade.add(new UnitUpgradeEntry(horizon, set(zenith)));
        unitUpgrade.add(new UnitUpgradeEntry(zenith, set(antumbra)));
        unitUpgrade.add(new UnitUpgradeEntry(antumbra, set(eclipse)));
        unitUpgrade.add(new UnitUpgradeEntry(elude, set(avert)));
        unitUpgrade.add(new UnitUpgradeEntry(avert, set(obviate)));
        unitUpgrade.add(new UnitUpgradeEntry(obviate, set(quell)));
        unitUpgrade.add(new UnitUpgradeEntry(quell, set(disrupt)));
        unitUpgrade.add(new UnitUpgradeEntry(retusa, set(oxynoe, risso)));
        unitUpgrade.add(new UnitUpgradeEntry(oxynoe, set(cyerce)));
        unitUpgrade.add(new UnitUpgradeEntry(cyerce, set(aegires)));
        unitUpgrade.add(new UnitUpgradeEntry(aegires, set(navanax)));
        unitUpgrade.add(new UnitUpgradeEntry(risso, set(minke)));
        unitUpgrade.add(new UnitUpgradeEntry(minke, set(bryde)));
        unitUpgrade.add(new UnitUpgradeEntry(bryde, set(sei)));
        unitUpgrade.add(new UnitUpgradeEntry(sei, set(omura)));
        unitUpgrade.add(new UnitUpgradeEntry(nova, set(pulsar, merui)));
        unitUpgrade.add(new UnitUpgradeEntry(pulsar, set(quasar)));
        unitUpgrade.add(new UnitUpgradeEntry(quasar, set(vela)));
        unitUpgrade.add(new UnitUpgradeEntry(vela, set(corvus)));
        unitUpgrade.add(new UnitUpgradeEntry(merui, set(cleroi)));
        unitUpgrade.add(new UnitUpgradeEntry(cleroi, set(anthicus)));
        unitUpgrade.add(new UnitUpgradeEntry(anthicus, set(tecta)));
        unitUpgrade.add(new UnitUpgradeEntry(tecta, set(collaris)));
        unitUpgrade.add(new UnitUpgradeEntry(mega, set(quad)));
        unitUpgrade.add(new UnitUpgradeEntry(quad, set(oct)));

        return unitUpgrade;
    }

    private List<UnitSpawnChance> _getUnitSpawnChance() {
        List<UnitSpawnChance> unitSpawnChance = new ArrayList<>();

        unitSpawnChance.add(new UnitSpawnChance(0.1, dagger, mace, atrax, pulsar, horizon));
        unitSpawnChance.add(new UnitSpawnChance(0.012, crawler, flare, oxynoe, locus, scepter));
        unitSpawnChance.add(new UnitSpawnChance(0.015, stell, merui, mega, bryde, scepter));
        unitSpawnChance.add(new UnitSpawnChance(0.018, risso, retusa, zenith, quad, sei));
        unitSpawnChance.add(new UnitSpawnChance(0.02, nova, poly, fortress, spiroct, tecta));
        unitSpawnChance.add(new UnitSpawnChance(0.014, dagger, atrax, vela, oxynoe, tecta));
        unitSpawnChance.add(new UnitSpawnChance(0.017, risso, minke, cyerce, antumbra, vanquish));
        unitSpawnChance.add(new UnitSpawnChance(0.021, pulsar, locus, horizon, oxynoe, bryde));
        unitSpawnChance.add(new UnitSpawnChance(0.016, dagger, flare, stell, oxynoe, merui));
        unitSpawnChance.add(new UnitSpawnChance(0.019, risso, pulsar, mega, zenith, vanquish));
        unitSpawnChance.add(new UnitSpawnChance(0.022, nova, fortress, mega, poly, locus));
        unitSpawnChance.add(new UnitSpawnChance(0.023, stell, retusa, mega, quad, tecta));
        unitSpawnChance.add(new UnitSpawnChance(0.013, dagger, mace, oxynoe, zenith, horizon));
        unitSpawnChance.add(new UnitSpawnChance(0.014, minke, oxynoe, retusa, zenith, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(0.015, crawler, horizon, poly, antumbra, sei));
        unitSpawnChance.add(new UnitSpawnChance(0.02, stell, mega, oxynoe, fortress, tecta));
        unitSpawnChance.add(new UnitSpawnChance(0.021, minke, oxynoe, locus, mega, scepter));
        unitSpawnChance.add(new UnitSpawnChance(0.023, risso, oxynoe, mega, locus, antumbra));
        unitSpawnChance.add(new UnitSpawnChance(0.017, flare, mace, spiroct, horizon, quell));
        unitSpawnChance.add(new UnitSpawnChance(0.016, flare, mace, atrax, mega, locus));
        unitSpawnChance.add(new UnitSpawnChance(0.02, dagger, fortress, minke, spiroct, sei));
        unitSpawnChance.add(new UnitSpawnChance(0.022, risso, retusa, cyerce, mega, oxynoe));
        unitSpawnChance.add(new UnitSpawnChance(0.019, pulsar, locus, bryde, minke, quad));
        unitSpawnChance.add(new UnitSpawnChance(0.021, minke, quasar, spiroct, bryde, oxynoe));
        unitSpawnChance.add(new UnitSpawnChance(0.015, dagger, oxynoe, locus, mega, quell));
        unitSpawnChance.add(new UnitSpawnChance(0.023, risso, zenith, mega, tecta, scepter));
        unitSpawnChance.add(new UnitSpawnChance(0.02, dagger, mace, quasar, spiroct, locus));
        unitSpawnChance.add(new UnitSpawnChance(0.022, pulsar, oxynoe, mega, quasar, sei));
        unitSpawnChance.add(new UnitSpawnChance(0.018, stell, mega, oxynoe, tecta, locus));
        unitSpawnChance.add(new UnitSpawnChance(0.019, dagger, quasar, fortress, minke, poly));
        unitSpawnChance.add(new UnitSpawnChance(0.024, flare, zenith, mega, spiroct, vanquish));
        unitSpawnChance.add(new UnitSpawnChance(0.017, crawler, pulsar, retusa, fortress, quell));
        unitSpawnChance.add(new UnitSpawnChance(0.021, pulsar, oxynoe, spiroct, tecta, mega));
        unitSpawnChance.add(new UnitSpawnChance(0.02, stell, fortress, horizon, spiroct, tecta));
        unitSpawnChance.add(new UnitSpawnChance(0.015, dagger, mace, mega, oxynoe, quell));
        unitSpawnChance.add(new UnitSpawnChance(0.022, minke, mega, fortress, pulsar, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(0.018, dagger, locus, bryde, zenith, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(0.023, minke, poly, zenith, fortress, mega));
        unitSpawnChance.add(new UnitSpawnChance(0.025, flare, mega, zenith, pulsar, quad, tecta));
        unitSpawnChance.add(new UnitSpawnChance(0.019, dagger, mega, pulsar, bryde, oxynoe, locus));
        unitSpawnChance.add(new UnitSpawnChance(0.02, flare, mega, quasar, locus, tecta, zenith));
        unitSpawnChance.add(new UnitSpawnChance(0.016, minke, horizon, fortress, retusa, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(0.021, minke, locus, fortress, quasar, poly));
        unitSpawnChance.add(new UnitSpawnChance(0.022, risso, minke, horizon, mega, fortress));
        unitSpawnChance.add(new UnitSpawnChance(0.024, stell, quasar, zenith, fortress, tecta));
        unitSpawnChance.add(new UnitSpawnChance(0.021, pulsar, locus, spiroct, retusa, poly));
        unitSpawnChance.add(new UnitSpawnChance(0.018, dagger, mace, fortress, mega, quasar));
        unitSpawnChance.add(new UnitSpawnChance(0.023, risso, pulsar, mega, locus, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(0.02, minke, quasar, fortress, spiroct, bryde));
        unitSpawnChance.add(new UnitSpawnChance(0.017, stell, mega, retusa, locus, tecta, poly));
        unitSpawnChance.add(new UnitSpawnChance(0.016, minke, locus, fortress, spiroct, poly));

        return unitSpawnChance;
    }

    @Data
    @NoArgsConstructor
    public static class UnitRespawnTimeEntry {
        @JsonSerialize(using = MappableContentSerializer.class)
        @JsonDeserialize(using = UnitTypeDeserializer.class)
        public UnitType unit;

        @JsonSerialize(using = DurationSerializer.class)
        @JsonDeserialize(using = DurationDeserializer.class)
        public Duration respawnTime;

        public UnitRespawnTimeEntry(UnitType unit, Duration respawnTime) {
            this.unit = unit;
            this.respawnTime = respawnTime;
        }
    }

    @Data
    @NoArgsConstructor
    public static class UnitExpEntry {
        @JsonSerialize(using = MappableContentSerializer.class)
        @JsonDeserialize(using = UnitTypeDeserializer.class)
        public UnitType unit;

        public int exp;

        public UnitExpEntry(UnitType unit, int exp) {
            this.unit = unit;
            this.exp = exp;
        }
    }

    @Data
    @NoArgsConstructor
    public static class BlockExpEntry {
        @JsonSerialize(using = MappableContentSerializer.class)
        @JsonDeserialize(using = BlockDeserializer.class)
        public Block block;

        public int exp;

        public BlockExpEntry(Block block, int exp) {
            this.block = block;
            this.exp = exp;
        }
    }

    @Data
    @NoArgsConstructor
    public static class BlockSpawnChanceEntry {
        @JsonSerialize(using = MappableContentSerializer.class)
        @JsonDeserialize(using = BlockDeserializer.class)
        public Block block;

        public double chance;

        public BlockSpawnChanceEntry(Block block, double chance) {
            this.block = block;
            this.chance = chance;
        }
    }

    @Data
    @NoArgsConstructor
    public static class UnitUpgradeEntry {
        @JsonSerialize(using = MappableContentSerializer.class)
        @JsonDeserialize(using = UnitTypeDeserializer.class)
        public UnitType unit;

        @JsonSerialize(contentUsing = MappableContentSerializer.class)
        @JsonDeserialize(contentUsing = UnitTypeDeserializer.class)
        public Set<UnitType> upgrades;

        public UnitUpgradeEntry(UnitType unit, Set<UnitType> upgrades) {
            this.unit = unit;
            this.upgrades = upgrades;
        }
    }

    @Data
    @NoArgsConstructor
    public static class UnitSpawnChance {
        @JsonDeserialize(contentUsing = UnitTypeDeserializer.class)
        @JsonSerialize(contentUsing = MappableContentSerializer.class)
        public List<UnitType> units = new ArrayList<>();
        public double chances = 0;

        public UnitSpawnChance(double chances, UnitType... units) {
            this.chances = chances;
            this.units = list(units);
        }
    }

    @SafeVarargs
    private static <T> HashSet<T> set(T... items) {
        var set = new HashSet<T>();
        for (var item : items) {
            set.add(item);
        }
        return set;
    }

    @SafeVarargs
    private static <T> ArrayList<T> list(T... items) {
        var list = new ArrayList<T>();
        for (var item : items) {
            list.add(item);
        }
        return list;
    }
}
