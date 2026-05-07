package plugin.type;

import java.time.Instant;

public class SessionData {
    // in ms
    public String name = new String();
    public long playTime = 0;
    public long lastSaved = Instant.now().toEpochMilli();
    public String trail = new String();
    public boolean griefer = false;
}
