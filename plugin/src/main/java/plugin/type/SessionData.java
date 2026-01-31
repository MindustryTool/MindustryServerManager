package plugin.type;

import java.util.concurrent.ConcurrentHashMap;

public class SessionData {
    public ConcurrentHashMap<Short, Long> kills = new ConcurrentHashMap<>();
    // in ms
    public long playTime = 0;
}
