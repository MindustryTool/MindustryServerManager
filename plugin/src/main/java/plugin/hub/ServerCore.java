package plugin.hub;

import lombok.AllArgsConstructor;
import lombok.Data;
import common.server.Server;

@Data
@AllArgsConstructor
public class ServerCore {
    private Server server;
    private final float x;
    private final float y;
    private final float size;
}
