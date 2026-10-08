package common.server;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerConfigMessage {
    String jwt;
    StartServer startServer;
}
