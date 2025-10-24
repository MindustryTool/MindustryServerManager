package events;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(chain = true, fluent = true)
public abstract class BaseEvent {

    public static final HashMap<String, Class<?>> eventTypeMap = new HashMap<>();

    {
        eventTypeMap.put("start", StartEvent.class);
        eventTypeMap.put("stop", StopEvent.class);
        eventTypeMap.put("server-stats", ServerStatsEvent.class);
        eventTypeMap.put("log", LogEvent.class);
    }

    private final UUID serverId;
    private final String name;
    private final Instant createdAt = Instant.now();

    public BaseEvent(UUID serverId, String name) {
        this.name = name;
        this.serverId = serverId;
    }
}
