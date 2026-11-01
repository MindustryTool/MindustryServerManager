package plugin.event;

import arc.util.Nullable;
import mindustry.gen.Player;

public class PlayerKickEvent implements ModerationEvent {

    public final @Nullable Player player;
    public final @Nullable String uuid;
    public final @Nullable String address;
    public final @Nullable String name;
    public final String actor;
    public final String reason;
    public final long timestamp;

    public PlayerKickEvent(@Nullable Player player, @Nullable String uuid, @Nullable String address,
            @Nullable String name, String reason, String actor) {
        this.player = player;
        this.uuid = uuid;
        this.address = address;
        this.name = name;
        this.reason = reason;
        this.actor = actor;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String actor() {
        return actor;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }
}
