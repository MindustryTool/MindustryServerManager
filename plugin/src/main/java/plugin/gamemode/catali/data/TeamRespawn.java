package plugin.gamemode.catali.data;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import mindustry.type.UnitType;
import plugin.json.MappableContentSerializer;
import plugin.json.SeqDeserializer;
import plugin.json.SeqSerializer;
import plugin.json.UnitTypeDeserializer;

import java.time.Duration;
import java.time.Instant;

import arc.struct.Seq;

public class TeamRespawn {
    @JsonSerialize(using = SeqSerializer.class)
    @JsonDeserialize(using = SeqDeserializer.class)
    public Seq<RespawnEntry> respawn = new Seq<>();

    public static class RespawnEntry {
        @JsonSerialize(using = MappableContentSerializer.class)
        @JsonDeserialize(using = UnitTypeDeserializer.class)
        public UnitType type;
        public Instant respawnAt;

        public RespawnEntry() {
        }

        public RespawnEntry(UnitType type, Duration duration) {
            this.type = type;
            this.respawnAt = Instant.now().plus(duration);
        }
    }

    public void addUnit(UnitType type, Duration duration) {
        respawn.add(new RespawnEntry(type, duration));
    }

    public Seq<RespawnEntry> getRespawnUnit() {
        var needRespawn = respawn.select(entry -> entry.respawnAt.isAfter(Instant.now()));

        if (needRespawn.size > 0) {
            respawn.removeAll(needRespawn);
        }

        return needRespawn;
    }
}
