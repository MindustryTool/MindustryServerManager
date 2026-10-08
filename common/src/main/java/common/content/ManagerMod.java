package dto;

import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ManagerModDto {
    private ModDto data;
    private List<UUID> servers;
}
