package plugin.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SessionData {
    // in ms
    public String name = "";
    public String locale = "";
    public long playTime = 0;
    public long lastSaved = Instant.now().toEpochMilli();
    public String trail = "";
    public boolean griefer = false;
    public long money = 0;
    public List<String> unlockedTrails = new ArrayList<>();
    public String dailyTaskDate = "";
    public Map<String, Integer> dailyTaskProgress = new HashMap<>();
    public int coreUpgradeLevel = 0;

    public volatile float exp = 0;
    
    public synchronized float addExp(float delta) {
        exp += delta;
        return exp;
    }

    public SessionData copy() {
        SessionData copy = new SessionData();

        copy.name = name;
        copy.locale = locale;
        copy.playTime = playTime;
        copy.exp = exp;
        copy.lastSaved = lastSaved;
        copy.trail = trail;
        copy.griefer = griefer;
        copy.money = money;
        copy.unlockedTrails = new ArrayList<>(unlockedTrails);
        copy.dailyTaskDate = dailyTaskDate;
        copy.dailyTaskProgress = new HashMap<>(dailyTaskProgress);
        copy.coreUpgradeLevel = coreUpgradeLevel;

        return copy;
    }
}
