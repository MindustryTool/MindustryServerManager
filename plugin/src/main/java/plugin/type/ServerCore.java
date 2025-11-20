package plugin.type;

import lombok.Data;
import dto.ServerDto;

@Data
public class ServerCore {
    private final ServerDto server;
    private final int x;
    private final int y;
}
