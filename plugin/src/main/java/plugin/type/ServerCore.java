package plugin.type;

import lombok.AllArgsConstructor;
import lombok.Data;
import dto.ServerDto;

@Data
@AllArgsConstructor
public class ServerCore {
    private final ServerDto server;
    private final float x;
    private final float y;
}
