package server.types.data;

import java.util.Optional;

import dto.ServerMetadata;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true, fluent = true)
public class ServerState {
    public boolean running;
    public Optional<ServerMetadata> meta;
}
