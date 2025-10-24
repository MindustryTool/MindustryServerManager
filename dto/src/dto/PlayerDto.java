package dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PlayerDto {
    private String name;
    private String uuid;
    private String locale;
    private String ip;
    private TeamDto team;
    private Boolean isAdmin;
    private Long joinedAt;
}
