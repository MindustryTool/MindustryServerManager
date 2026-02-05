package plugin.type;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class SessionData {
    public ConcurrentHashMap<Short, Long> kills = new ConcurrentHashMap<>();
    // in ms
    public String name = new String();
    public long playTime = 0;
    public long lastSaved = Instant.now().toEpochMilli();
    public String trail = new String();
}
