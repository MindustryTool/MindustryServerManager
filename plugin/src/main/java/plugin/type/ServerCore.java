package plugin.type;

import lombok.Data;
import dto.ServerResponseData;

@Data
public class ServerCore {
    private final ServerResponseData server;
    private final int x;
    private final int y;
}
