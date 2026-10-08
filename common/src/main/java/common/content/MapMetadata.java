package common.content;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MapMetadata {
    private String name;
    private String filename;
    private int width;
    private int height;
    private boolean isCustom;
}
