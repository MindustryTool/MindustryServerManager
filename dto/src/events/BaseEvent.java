package events;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;

@Getter
@Accessors(chain = true)
@ToString
public abstract class BaseEvent {

    public static final HashMap<String, Class<?>> eventTypeMap = new HashMap<>();

    {
        eventTypeMap.put("start", StartEvent.class);
        eventTypeMap.put("stop", StopEvent.class);
        eventTypeMap.put("server-state", ServerStateEvent.class);
        eventTypeMap.put("log", LogEvent.class);
    }

    private UUID serverId;
    private String name;
    private Instant createdAt = Instant.now();

    public BaseEvent(UUID serverId, String name) {
        this.name = name;
        this.serverId = serverId;
    }
}
