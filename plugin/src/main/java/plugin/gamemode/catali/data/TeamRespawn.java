package plugin.gamemode.catali.data;

import mindustry.type.UnitType;

import java.time.Duration;
import java.time.Instant;

import arc.struct.Seq;

public class TeamRespawn {
    public Seq<RespawnEntry> respawn = new Seq<>();

    public static class RespawnEntry {
        public UnitType type;
        public Instant respawnAt;

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
