package events;

import java.util.UUID;

import lombok.experimental.Accessors;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import enums.NodeRemoveReason;

@Accessors(chain = true)
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class StopEvent extends BaseEvent {

    private String reason;

    public StopEvent(UUID serverId, NodeRemoveReason reason) {
        super(serverId, "stop");
        this.reason = reason.name();
    }

    public StopEvent(UUID serverId, String reason) {
        super(serverId, "stop");
        this.reason = reason;
    }
}
