package dto;

import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PlayerInfoPageDto {
    public int items;
    public int page;
    public List<PlayerInfoDto> data;
}
