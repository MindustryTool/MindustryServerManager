package plugin.gamemode.catali.data;

import mindustry.type.UnitType;
import java.time.Duration;
import java.time.Instant;

import arc.struct.Seq;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TeamRespawn {
    public Seq<RespawnEntry> respawn = new Seq<>();

    public void addUnit(UnitType type, Duration duration) {
        respawn.add(new RespawnEntry(type, duration));
    }

    public Seq<RespawnEntry> getRespawnUnit() {
        var needRespawn = respawn.select(entry -> entry.respawnAt.isBefore(Instant.now()));

        return needRespawn;
    }
}
