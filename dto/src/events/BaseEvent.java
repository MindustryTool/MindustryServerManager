package events;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import arc.util.Log;
import lombok.Getter;
import lombok.experimental.Accessors;

@Getter
@Accessors(chain = true, fluent = true)
public abstract class BaseEvent {

    private static final HashMap<String, Class<?>> eventTypeMap = new HashMap<>();

    private final UUID serverId;
    private final String name;
    private final Instant createdAt = Instant.now();

    public BaseEvent(UUID serverId, String name) {
        this.name = name;
        this.serverId = serverId;

        eventTypeMap.put(name, getClass());

        Log.info("Event " + name + " registered with class " + getClass().getSimpleName());
    }
}
