package events;

import java.util.List;
import java.util.UUID;

import dto.ServerStateDto;

public class ServerStateEvent extends BaseEvent {

    public final List<ServerStateDto> state;

    public ServerStateEvent(UUID serverId, List<ServerStateDto> state) {
        super(serverId, "server-state");
        this.state = state;
    }
}
