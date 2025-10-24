package server.types.event;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import server.types.data.ServerMetadata;

@Getter
@Setter
@Accessors(chain = true, fluent = true)
public class StartEvent extends BaseEvent {
    private final ServerMetadata metadata;

    public StartEvent(UUID serverId, ServerMetadata metadata) {
        super(serverId, "start");
        this.metadata = metadata;
    }
}
