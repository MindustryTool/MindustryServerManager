package dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class HostDto {
    private String gameMode;
    private String mapName;
}
