package common.player;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TeamInfo {
    private String name;
    private String color;
}
