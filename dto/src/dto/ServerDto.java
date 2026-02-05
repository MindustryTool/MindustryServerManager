package dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Accessors(chain = true)
public class ServerDto {
    private UUID id;
    private UUID userId;
    private String name;
    private String description;
    private String mode;
    private int port;
    private ServerStatus status = ServerStatus.UNSET;
    private boolean official;
    private boolean hub;
    private long players;
    private String mapName;
    private String gameVersion;
    private List<String> mods = new ArrayList<>();
}
