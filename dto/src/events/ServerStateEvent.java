package events;

import java.util.List;
import java.util.UUID;

import dto.ServerStateDto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ServerStateEvent extends BaseEvent {

    public List<ServerStateDto> state;

    public ServerStateEvent(UUID serverId, List<ServerStateDto> state) {
        super(serverId, "server-state");
        this.state = state;
    }
}
