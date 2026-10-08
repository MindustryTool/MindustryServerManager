package common.content;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Mod {
    private String name;
    private String filename;
    private ModMetadata meta;

}
