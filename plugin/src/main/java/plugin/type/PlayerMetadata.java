package plugin.type;

import java.time.Instant;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustry.gen.Player;

@Data
@Accessors(chain = true)
public class PlayerMetadata {
    Player player;
    long exp;
    String name;
    boolean isLoggedIn;
    Instant createdAt = Instant.now();
}
