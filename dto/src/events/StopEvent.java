package events;

import java.util.UUID;

import lombok.experimental.Accessors;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Accessors(chain = true)
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class StopEvent extends BaseEvent {

    public StopEvent(UUID serverId) {
        super(serverId, "stop");
    }
}
