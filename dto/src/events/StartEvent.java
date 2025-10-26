package events;

import java.util.UUID;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
@EqualsAndHashCode(callSuper = false)
public class StartEvent extends BaseEvent {

    public StartEvent(UUID serverId) {
        super(serverId, "start");
    }
}
