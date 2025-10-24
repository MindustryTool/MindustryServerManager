package events;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true, fluent = true)
public class StopEvent extends BaseEvent {

    public StopEvent(UUID serverId) {
        super(serverId, "stop");
    }
}
