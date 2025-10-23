package mindustrytool.servermanager.types.response;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
public class StatsDto {
    private UUID id;
    private String mapName = "";
    private List<ModDto> mods = new ArrayList<>();
    private String version = "UNSET";

    private List<PlayerDto> players = new ArrayList<>();

    private ServerStatus status = ServerStatus.MANAGER_UNSET;
    
    private int kicks;

    private boolean isPaused = false;
    private boolean isHosting = false;
    
    // -1 Mean unset
    private Long startedAt = -1l;

    public static enum ServerStatus {
        UNSET,
        MANAGER_UNSET,
        PAUSED,
        NOT_RESPONSE
    }
}
