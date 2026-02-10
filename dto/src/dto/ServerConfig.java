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

    private int port;

    private Map<String, String> env;

    private String image;

    private String hostCommand;

    private Boolean isHub;

    private Boolean isAutoTurnOff;

    private Boolean isDefault;

    private float cpu;

    private int memory;
}
