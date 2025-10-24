package plugin.type;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    @JsonProperty("isAdmin")
    private boolean isAdmin;
    private Long joinedAt;
}
