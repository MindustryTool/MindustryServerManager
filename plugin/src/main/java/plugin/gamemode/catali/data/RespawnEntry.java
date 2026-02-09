package plugin.gamemode.catali.data;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import mindustry.type.UnitType;
import plugin.json.MappableContentSerializer;
import plugin.json.UnitTypeDeserializer;

@Data
@NoArgsConstructor
public class RespawnEntry {
    @JsonSerialize(using = MappableContentSerializer.class)
    @JsonDeserialize(using = UnitTypeDeserializer.class)
    @NonNull
    public UnitType type;

    @NonNull
    public Instant respawnAt;

    public RespawnEntry(UnitType type, Duration duration) {
        this.type = type;
        this.respawnAt = Instant.now().plus(duration);
    }
}
