package plugin.gamemode.catali;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import lombok.Data;
import lombok.NoArgsConstructor;
import mindustry.type.UnitType;
import mindustry.world.Block;
import plugin.annotations.Component;
import plugin.annotations.Configuration;
import plugin.json.DurationDeserializer;
import plugin.json.DurationSerializer;
import plugin.json.MappableContentDeserializer;
import plugin.json.MappableContentKeyDeserializer;
import plugin.json.MappableContentSerializer;
import plugin.json.MappableKeySerializer;

import static mindustry.content.UnitTypes.*;
import static mindustry.content.Blocks.*;

@Configuration("catali.json")
@NoArgsConstructor
@Component
public class CataliConfig {

    @JsonSerialize(using = DurationSerializer.class)
    @JsonDeserialize(using = DurationDeserializer.class)
    public Duration enemyUnitSpawnTime = Duration.ofMinutes(1);

    @JsonSerialize(using = DurationSerializer.class)
    @JsonDeserialize(using = DurationDeserializer.class)
    public Duration enemyBlockSpawnTime = Duration.ofMinutes(2);

    @JsonSerialize(keyUsing = MappableKeySerializer.class, contentUsing = DurationSerializer.class)
    @JsonDeserialize(keyUsing = MappableContentKeyDeserializer.class, contentUsing = DurationDeserializer.class)
    public Map<UnitType, Duration> unitRespawnTime = new HashMap<>();

    @JsonSerialize(keyUsing = MappableKeySerializer.class)
    @JsonDeserialize(keyUsing = MappableContentKeyDeserializer.class)
    public Map<UnitType, Integer> unitExp = new HashMap<>();

    @JsonSerialize(keyUsing = MappableKeySerializer.class)
    @JsonDeserialize(keyUsing = MappableContentKeyDeserializer.class)
    public Map<Block, Integer> blockExp = new HashMap<>();

    @JsonSerialize(keyUsing = MappableKeySerializer.class)
    @JsonDeserialize(keyUsing = MappableContentKeyDeserializer.class)
    public Map<Block, Integer> blockSpawnChance = new HashMap<>();

    @JsonSerialize(keyUsing = MappableKeySerializer.class)
    @JsonDeserialize(keyUsing = MappableContentKeyDeserializer.class)
    public Map<UnitType, Set<UnitType>> unitUpgrade = new HashMap<>();

    public List<UnitSpawnChance> unitSpawnChance = new ArrayList<>();

    {
        unitExp.put(dagger, 30);
        unitExp.put(nova, 24);
        unitExp.put(flare, 14);
        unitExp.put(poly, 80);
        unitExp.put(mace, 110);
        unitExp.put(pulsar, 64);
        unitExp.put(horizon, 68);
        unitExp.put(mega, 92);
        unitExp.put(risso, 60);
        unitExp.put(retusa, 54);
        unitExp.put(stell, 170);
        unitExp.put(merui, 136);
        unitExp.put(elude, 120);
        unitExp.put(fortress, 180);
        unitExp.put(quasar, 128);
        unitExp.put(zenith, 140);
        unitExp.put(quad, 1200);
        unitExp.put(minke, 120);
        unitExp.put(oxynoe, 112);
        unitExp.put(locus, 420);
        unitExp.put(cleroi, 220);
        unitExp.put(avert, 220);
        unitExp.put(scepter, 1800);
        unitExp.put(vela, 1640);
        unitExp.put(antumbra, 1440);
        unitExp.put(oct, 3000);
        unitExp.put(bryde, 182);
        unitExp.put(cyerce, 174);
        unitExp.put(precept, 1000);
        unitExp.put(anthicus, 580);
        unitExp.put(obviate, 460);
        unitExp.put(reign, 4000);
        unitExp.put(corvus, 3000);
        unitExp.put(eclipse, 4000);
        unitExp.put(sei, 2000);
        unitExp.put(aegires, 2200);
        unitExp.put(vanquish, 2200);
        unitExp.put(tecta, 1460);
        unitExp.put(quell, 1200);
        unitExp.put(omura, 4400);
        unitExp.put(navanax, 4000);
        unitExp.put(conquer, 4400);
        unitExp.put(collaris, 3600);
        unitExp.put(disrupt, 2400);

        unitRespawnTime.put(dagger, Duration.ofSeconds(60));
        unitRespawnTime.put(nova, Duration.ofSeconds(60));
        unitRespawnTime.put(crawler, Duration.ofSeconds(60));
        unitRespawnTime.put(flare, Duration.ofSeconds(60));
        unitRespawnTime.put(poly, Duration.ofSeconds(30));
        unitRespawnTime.put(risso, Duration.ofSeconds(80));
        unitRespawnTime.put(retusa, Duration.ofSeconds(80));
        unitRespawnTime.put(stell, Duration.ofSeconds(80));
        unitRespawnTime.put(merui, Duration.ofSeconds(80));
        unitRespawnTime.put(mace, Duration.ofSeconds(100));
        unitRespawnTime.put(pulsar, Duration.ofSeconds(100));
        unitRespawnTime.put(atrax, Duration.ofSeconds(100));
        unitRespawnTime.put(horizon, Duration.ofSeconds(100));
        unitRespawnTime.put(minke, Duration.ofSeconds(120));
        unitRespawnTime.put(oxynoe, Duration.ofSeconds(120));
        unitRespawnTime.put(locus, Duration.ofSeconds(120));
        unitRespawnTime.put(cleroi, Duration.ofSeconds(120));
        unitRespawnTime.put(avert, Duration.ofSeconds(100));
        unitRespawnTime.put(fortress, Duration.ofSeconds(140));
        unitRespawnTime.put(quasar, Duration.ofSeconds(140));
        unitRespawnTime.put(spiroct, Duration.ofSeconds(140));
        unitRespawnTime.put(zenith, Duration.ofSeconds(140));
        unitRespawnTime.put(mega, Duration.ofSeconds(60));
        unitRespawnTime.put(bryde, Duration.ofSeconds(180));
        unitRespawnTime.put(cyerce, Duration.ofSeconds(180));
        unitRespawnTime.put(precept, Duration.ofSeconds(180));
        unitRespawnTime.put(anthicus, Duration.ofSeconds(180));
        unitRespawnTime.put(obviate, Duration.ofSeconds(140));
        unitRespawnTime.put(scepter, Duration.ofSeconds(200));
        unitRespawnTime.put(vela, Duration.ofSeconds(200));
        unitRespawnTime.put(arkyid, Duration.ofSeconds(200));
        unitRespawnTime.put(antumbra, Duration.ofSeconds(200));
        unitRespawnTime.put(quad, Duration.ofSeconds(160));
        unitRespawnTime.put(sei, Duration.ofSeconds(240));
        unitRespawnTime.put(aegires, Duration.ofSeconds(240));
        unitRespawnTime.put(vanquish, Duration.ofSeconds(240));
        unitRespawnTime.put(tecta, Duration.ofSeconds(240));
        unitRespawnTime.put(quell, Duration.ofSeconds(180));
        unitRespawnTime.put(reign, Duration.ofSeconds(240));
        unitRespawnTime.put(corvus, Duration.ofSeconds(240));
        unitRespawnTime.put(toxopid, Duration.ofSeconds(240));
        unitRespawnTime.put(eclipse, Duration.ofSeconds(240));
        unitRespawnTime.put(oct, Duration.ofSeconds(160));
        unitRespawnTime.put(omura, Duration.ofSeconds(300));
        unitRespawnTime.put(navanax, Duration.ofSeconds(300));
        unitRespawnTime.put(conquer, Duration.ofSeconds(300));
        unitRespawnTime.put(collaris, Duration.ofSeconds(300));
        unitRespawnTime.put(disrupt, Duration.ofSeconds(220));

        blockExp.put(copperWall, 32);
        blockExp.put(copperWallLarge, 128);
        blockExp.put(titaniumWall, 44);
        blockExp.put(titaniumWallLarge, 176);
        blockExp.put(berylliumWall, 52);
        blockExp.put(berylliumWallLarge, 208);
        blockExp.put(plastaniumWall, 52);
        blockExp.put(plastaniumWallLarge, 208);
        blockExp.put(tungstenWall, 72);
        blockExp.put(tungstenWallLarge, 288);
        blockExp.put(thoriumWall, 80);
        blockExp.put(thoriumWallLarge, 320);
        blockExp.put(phaseWall, 60);
        blockExp.put(phaseWallLarge, 240);
        blockExp.put(surgeWall, 92);
        blockExp.put(surgeWallLarge, 368);
        blockExp.put(carbideWall, 108);
        blockExp.put(carbideWallLarge, 432);
        blockExp.put(reinforcedSurgeWall, 100);
        blockExp.put(reinforcedSurgeWallLarge, 400);
        blockExp.put(container, 75);
        blockExp.put(vault, 225);
        blockExp.put(reinforcedContainer, 250);
        blockExp.put(reinforcedVault, 750);

        blockSpawnChance.put(copperWall, 200);
        blockSpawnChance.put(copperWallLarge, 50);
        blockSpawnChance.put(titaniumWall, 100);
        blockSpawnChance.put(titaniumWallLarge, 20);
        blockSpawnChance.put(berylliumWall, 67);
        blockSpawnChance.put(berylliumWallLarge, 13);
        blockSpawnChance.put(plastaniumWall, 57);
        blockSpawnChance.put(plastaniumWallLarge, 11);
        blockSpawnChance.put(tungstenWall, 50);
        blockSpawnChance.put(tungstenWallLarge, 10);
        blockSpawnChance.put(thoriumWall, 50);
        blockSpawnChance.put(thoriumWallLarge, 9);
        blockSpawnChance.put(phaseWall, 40);
        blockSpawnChance.put(phaseWallLarge, 8);
        blockSpawnChance.put(surgeWall, 25);
        blockSpawnChance.put(surgeWallLarge, 5);
        blockSpawnChance.put(carbideWall, 20);
        blockSpawnChance.put(carbideWallLarge, 4);
        blockSpawnChance.put(reinforcedSurgeWall, 17);
        blockSpawnChance.put(reinforcedSurgeWallLarge, 3);
        blockSpawnChance.put(container, 10);
        blockSpawnChance.put(vault, 4);
        blockSpawnChance.put(reinforcedContainer, 4);
        blockSpawnChance.put(reinforcedVault, 1);
        blockSpawnChance.put(thoriumReactor, 1);

        unitUpgrade.put(poly, set(dagger, flare, retusa, nova, mega));
        unitUpgrade.put(dagger, set(mace, atrax, stell));
        unitUpgrade.put(mace, set(fortress));
        unitUpgrade.put(fortress, set(scepter));
        unitUpgrade.put(scepter, set(reign));
        unitUpgrade.put(atrax, set(spiroct));
        unitUpgrade.put(spiroct, set(arkyid));
        unitUpgrade.put(arkyid, set(toxopid));
        unitUpgrade.put(stell, set(locus));
        unitUpgrade.put(locus, set(precept));
        unitUpgrade.put(precept, set(vanquish));
        unitUpgrade.put(vanquish, set(conquer));
        unitUpgrade.put(flare, set(horizon, elude));
        unitUpgrade.put(horizon, set(zenith));
        unitUpgrade.put(zenith, set(antumbra));
        unitUpgrade.put(antumbra, set(eclipse));
        unitUpgrade.put(elude, set(avert));
        unitUpgrade.put(avert, set(obviate));
        unitUpgrade.put(obviate, set(quell));
        unitUpgrade.put(quell, set(disrupt));
        unitUpgrade.put(retusa, set(oxynoe, risso));
        unitUpgrade.put(oxynoe, set(cyerce));
        unitUpgrade.put(cyerce, set(aegires));
        unitUpgrade.put(aegires, set(navanax));
        unitUpgrade.put(risso, set(minke));
        unitUpgrade.put(minke, set(bryde));
        unitUpgrade.put(bryde, set(sei));
        unitUpgrade.put(sei, set(omura));
        unitUpgrade.put(nova, set(pulsar, merui));
        unitUpgrade.put(pulsar, set(quasar));
        unitUpgrade.put(quasar, set(vela));
        unitUpgrade.put(vela, set(corvus));
        unitUpgrade.put(merui, set(cleroi));
        unitUpgrade.put(cleroi, set(anthicus));
        unitUpgrade.put(anthicus, set(tecta));
        unitUpgrade.put(tecta, set(collaris));
        unitUpgrade.put(mega, set(quad));
        unitUpgrade.put(quad, set(oct));

        unitSpawnChance.add(new UnitSpawnChance(10, dagger, mace, atrax, pulsar, horizon));
        unitSpawnChance.add(new UnitSpawnChance(12, crawler, flare, oxynoe, locus, scepter));
        unitSpawnChance.add(new UnitSpawnChance(15, stell, merui, mega, bryde, scepter));
        unitSpawnChance.add(new UnitSpawnChance(18, risso, retusa, zenith, quad, sei));
        unitSpawnChance.add(new UnitSpawnChance(20, nova, poly, fortress, spiroct, tecta));
        unitSpawnChance.add(new UnitSpawnChance(14, dagger, atrax, vela, oxynoe, tecta));
        unitSpawnChance.add(new UnitSpawnChance(17, risso, minke, cyerce, antumbra, vanquish));
        unitSpawnChance.add(new UnitSpawnChance(21, pulsar, locus, horizon, oxynoe, bryde));
        unitSpawnChance.add(new UnitSpawnChance(16, dagger, flare, stell, oxynoe, merui));
        unitSpawnChance.add(new UnitSpawnChance(19, risso, pulsar, mega, zenith, vanquish));
        unitSpawnChance.add(new UnitSpawnChance(22, nova, fortress, mega, poly, locus));
        unitSpawnChance.add(new UnitSpawnChance(23, stell, retusa, mega, quad, tecta));
        unitSpawnChance.add(new UnitSpawnChance(13, dagger, mace, oxynoe, zenith, horizon));
        unitSpawnChance.add(new UnitSpawnChance(14, minke, oxynoe, retusa, zenith, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(18, crawler, horizon, poly, antumbra, sei));
        unitSpawnChance.add(new UnitSpawnChance(20, stell, mega, oxynoe, fortress, tecta));
        unitSpawnChance.add(new UnitSpawnChance(21, minke, oxynoe, locus, mega, scepter));
        unitSpawnChance.add(new UnitSpawnChance(23, risso, oxynoe, mega, locus, antumbra));
        unitSpawnChance.add(new UnitSpawnChance(17, flare, mace, spiroct, horizon, quell));
        unitSpawnChance.add(new UnitSpawnChance(16, flare, mace, atrax, mega, locus));
        unitSpawnChance.add(new UnitSpawnChance(20, dagger, fortress, minke, spiroct, sei));
        unitSpawnChance.add(new UnitSpawnChance(22, risso, retusa, cyerce, mega, oxynoe));
        unitSpawnChance.add(new UnitSpawnChance(19, pulsar, locus, bryde, minke, quad));
        unitSpawnChance.add(new UnitSpawnChance(21, minke, quasar, spiroct, bryde, oxynoe));
        unitSpawnChance.add(new UnitSpawnChance(15, dagger, oxynoe, locus, mega, quell));
        unitSpawnChance.add(new UnitSpawnChance(23, risso, zenith, mega, tecta, scepter));
        unitSpawnChance.add(new UnitSpawnChance(20, dagger, mace, quasar, spiroct, locus));
        unitSpawnChance.add(new UnitSpawnChance(22, pulsar, oxynoe, mega, quasar, sei));
        unitSpawnChance.add(new UnitSpawnChance(18, stell, mega, oxynoe, tecta, locus));
        unitSpawnChance.add(new UnitSpawnChance(19, dagger, quasar, fortress, minke, poly));
        unitSpawnChance.add(new UnitSpawnChance(24, flare, zenith, mega, spiroct, vanquish));
        unitSpawnChance.add(new UnitSpawnChance(17, crawler, pulsar, retusa, fortress, quell));
        unitSpawnChance.add(new UnitSpawnChance(21, pulsar, oxynoe, spiroct, tecta, mega));
        unitSpawnChance.add(new UnitSpawnChance(20, stell, fortress, horizon, spiroct, tecta));
        unitSpawnChance.add(new UnitSpawnChance(15, dagger, mace, mega, oxynoe, quell));
        unitSpawnChance.add(new UnitSpawnChance(22, minke, mega, fortress, pulsar, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(18, dagger, locus, bryde, zenith, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(23, minke, poly, zenith, fortress, mega));
        unitSpawnChance.add(new UnitSpawnChance(25, flare, mega, zenith, pulsar, quad, tecta));
        unitSpawnChance.add(new UnitSpawnChance(19, dagger, mega, pulsar, bryde, oxynoe, locus));
        unitSpawnChance.add(new UnitSpawnChance(20, flare, mega, quasar, locus, tecta, zenith));
        unitSpawnChance.add(new UnitSpawnChance(16, minke, horizon, fortress, retusa, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(21, minke, locus, fortress, quasar, poly));
        unitSpawnChance.add(new UnitSpawnChance(22, risso, minke, horizon, mega, fortress));
        unitSpawnChance.add(new UnitSpawnChance(24, stell, quasar, zenith, fortress, tecta));
        unitSpawnChance.add(new UnitSpawnChance(21, pulsar, locus, spiroct, retusa, poly));
        unitSpawnChance.add(new UnitSpawnChance(18, dagger, mace, fortress, mega, quasar));
        unitSpawnChance.add(new UnitSpawnChance(23, risso, pulsar, mega, locus, spiroct));
        unitSpawnChance.add(new UnitSpawnChance(20, minke, quasar, fortress, spiroct, bryde));
        unitSpawnChance.add(new UnitSpawnChance(17, stell, mega, retusa, locus, tecta, poly));
        unitSpawnChance.add(new UnitSpawnChance(16, minke, locus, fortress, spiroct, poly));

    }

    @Data
    public static class UnitSpawnChance {
        @JsonDeserialize(using = MappableContentDeserializer.class)
        @JsonSerialize(using = MappableContentSerializer.class)
        public int chances = 0;
        public List<UnitType> units = new ArrayList<>();

        public UnitSpawnChance(int chances, UnitType... units) {
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
