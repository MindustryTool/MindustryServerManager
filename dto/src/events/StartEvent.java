package events;

import java.util.UUID;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class StartEvent extends BaseEvent {

    public StartEvent(UUID serverId) {
        super(serverId, "start");
    }
}
