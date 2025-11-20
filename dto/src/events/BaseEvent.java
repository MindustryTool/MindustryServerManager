package events;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;

@Getter
@Accessors(chain = true)
@ToString
@NoArgsConstructor
public abstract class BaseEvent {

    private UUID serverId;
    private String name;

    private Instant createdAt = Instant.now();

    public BaseEvent(UUID serverId, String name) {
        this.name = name;
        this.serverId = serverId;
    }
}
