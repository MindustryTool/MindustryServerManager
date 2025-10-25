package events;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class StartEvent extends BaseEvent {
    public StartEvent(UUID serverId) {
        super(serverId, "start");
    }
}
