package mindustrytool.servermanager.types.event;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import mindustrytool.servermanager.types.data.ServerMetadata;

@Getter
@Setter
@Accessors(chain = true, fluent = true)
public class StopEvent extends BaseEvent {
    private final ServerMetadata metadata;

    public StopEvent(UUID serverId, ServerMetadata metadata) {
        super(serverId, "stop");
        this.metadata = metadata;
    }
}
