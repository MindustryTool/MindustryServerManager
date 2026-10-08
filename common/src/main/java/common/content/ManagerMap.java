package common.content;

import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ManagerMap {
    private MapMetadata metadata;
    private List<UUID> servers;
}
