package plugin.hub;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ServerCore {
    private HubEntry entry;
    private final float x;
    private final float y;
    private final float size;
}
