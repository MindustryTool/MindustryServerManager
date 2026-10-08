package common.network;

import java.util.ArrayList;
import java.util.List;

import common.server.Server;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ApiServer {
    private List<Server> servers = new ArrayList<Server>();
}
