package mindustrytool.servermanager.types.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PlayerDto {
    private String uuid;
    private String name;
    private String ip;
    private TeamDto team;
    private String locale;

    @JsonProperty("isAdmin")
    private boolean isAdmin;
    private Long joinedAt;
}
