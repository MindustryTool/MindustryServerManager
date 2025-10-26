package events;

import java.util.UUID;

import lombok.experimental.Accessors;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Accessors(chain = true)
@Data
@EqualsAndHashCode(callSuper = false)
public class StopEvent extends BaseEvent {

    public StopEvent(UUID serverId) {
        super(serverId, "stop");
    }
}
