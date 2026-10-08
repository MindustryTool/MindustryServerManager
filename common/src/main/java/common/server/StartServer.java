package common.server;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StartServer {
    String mapName;
    String mode;
    String hostCommand;
}
