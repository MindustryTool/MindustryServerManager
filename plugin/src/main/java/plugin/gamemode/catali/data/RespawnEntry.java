package plugin.gamemode.catali.data;

import java.time.Duration;
import java.time.Instant;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import mindustry.type.UnitType;

@Data
@NoArgsConstructor
public class RespawnEntry {
    @NonNull
    public UnitType type;

    @NonNull
    public Instant respawnAt;

    public RespawnEntry(UnitType type, Duration duration) {
        this.type = type;
        this.respawnAt = Instant.now().plus(duration);
    }
}
