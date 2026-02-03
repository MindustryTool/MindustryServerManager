package plugin.type;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class SessionData {
    public ConcurrentHashMap<Short, Long> kills = new ConcurrentHashMap<>();
    // in ms
    public long playTime = 0;
    public long joinedAt = Instant.now().toEpochMilli();
}
