package dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerStateDto {
    private UUID serverId;
    private List<PlayerDto> players = new ArrayList<>();
    private String mapName = "DEBUG";
    private List<ModDto> mods = new ArrayList<>();
    private ServerStatus status = ServerStatus.UNSET;
    private int kicks = 0;
    private String version = "custom";
    private Long startedAt = Instant.now().toEpochMilli();
}
