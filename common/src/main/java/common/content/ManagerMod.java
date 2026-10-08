package common.content;

import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ManagerMod {
    private Mod data;
    private List<UUID> servers;
}
