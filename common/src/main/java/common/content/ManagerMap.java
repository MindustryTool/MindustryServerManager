package dto;

import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ManagerMapDto {
    private MapDto metadata;
    private List<UUID> servers;
}
