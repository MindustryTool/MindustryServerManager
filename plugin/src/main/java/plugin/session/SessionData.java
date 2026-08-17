package plugin.session;

import java.time.Instant;

public class SessionData {
    // in ms
    public String name = "";
    public String locale = "";
    public long playTime = 0;
    public float exp = 0;
    public long lastSaved = Instant.now().toEpochMilli();
    public String trail = "";
    public boolean griefer = false;
}
