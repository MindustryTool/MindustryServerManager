package server.types.data;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerConfig {
    private UUID id;

    private UUID userId;

    @NotEmpty
    @Size(max = 128)
    private String name;

    @NotEmpty
    @Size(max = 2048)
    private String description;

    @NotEmpty
    @Size(max = 256)
    private String mode;

    
    @Min(1000)
    @Max(900000)
    private int port;
    
    private Map<String, String> env;

    @NotEmpty
    @Size(max = 256)
    private String image;
    
    @Size(max = 256)
    private String hostCommand;

    private boolean isHub;
    private boolean isAutoTurnOff;
    private boolean isDefault;

    private float cpu;
    private int memory;
}
