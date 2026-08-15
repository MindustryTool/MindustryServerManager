package plugin.event;

import arc.util.Nullable;
import mindustry.net.Packets.KickReason;

public class KickEvent {
    public final @Nullable String address;
    public final @Nullable String uuid;
    public final @Nullable String reason;
    public final long time;

    public KickEvent(String address, String uuid, String reason) {
        this.address = address;
        this.uuid = uuid;
        this.reason = reason;
        this.time = System.currentTimeMillis();
    }

    public @Nullable KickReason getKickReason() {
        try {
            return KickReason.valueOf(reason);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
