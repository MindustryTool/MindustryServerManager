package common.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import common.content.Mod;
import common.player.PlayerInfo;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerSnapshot {
    private UUID serverId;
    private List<PlayerInfo> players = new ArrayList<>();
    private String mapName = "DEBUG";
    private List<Mod> mods = new ArrayList<>();
    private ServerStatus status = ServerStatus.UNSET;
    private int kicks = 0;
    private String version = "custom";
    private Long startedAt = Instant.now().toEpochMilli();
    private String pluginHash;
}
