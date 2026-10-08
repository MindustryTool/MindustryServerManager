package dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ModDto {
    private String name;
    private String filename;
    private ModMetaDto meta;

}
