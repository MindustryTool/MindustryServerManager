package plugin.type;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StartServerDto {
    String mapName;
    String mode;
    String hostCommand;
}
