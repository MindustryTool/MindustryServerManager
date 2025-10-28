package events;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;

@Getter
@Accessors(chain = true)
@ToString
@NoArgsConstructor
public abstract class BaseEvent {

    private static final HashMap<String, Class<?>> eventTypeMap = new HashMap<>();

    public static HashMap<String, Class<?>> getEventMap() {
        if (eventTypeMap.size() == 0) {
            eventTypeMap.put("start", StartEvent.class);
            eventTypeMap.put("stop", StopEvent.class);
            eventTypeMap.put("server-state", ServerStateEvent.class);
            eventTypeMap.put("log", LogEvent.class);
        }

        return eventTypeMap;
    }

    private UUID serverId;
    private String name;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss.SSS", timezone="UTC")
    private Instant createdAt = Instant.now();

    public BaseEvent(UUID serverId, String name) {
        this.name = name;
        this.serverId = serverId;
    }
}
