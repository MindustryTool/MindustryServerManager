package plugin.type;

import java.util.concurrent.ConcurrentHashMap;

import mindustry.type.UnitType;

public class SessionData {
    public ConcurrentHashMap<Short, Long> kills = new ConcurrentHashMap<>();

    public void addKill(UnitType unit, int amount) {
        assert amount > 0 : "Kill amount must be greater than 0";
        kills.put(unit.id, kills.getOrDefault(unit.id, 0L) + amount);
    }
}
