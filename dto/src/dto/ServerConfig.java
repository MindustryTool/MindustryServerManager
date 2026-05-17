package dto;

import java.util.Map;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerConfig {
    private UUID id;

    private UUID userId;

    private String name;

    private String description;

    private String mode;
    
    private String gamemode;

    private int port;

    private Map<String, String> env;

    private String image;

    private String hostCommand;

    private Boolean isHub = false;

    private Boolean isAutoTurnOff = true;

    private Boolean isDefault = false;

    private Boolean isOfficial = false;

    private float cpu;

    private int memory;
}
