package dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerResponseData {
    public UUID id;
    public UUID userId;
    public String name;
    public String description;
    public String mode;
    public int port;
    public String hostCommand;
    public String discordChannelId;
    public String status = "UNSET";
    public boolean official;
    public long ramUsage;
    public long totalRam;
    public long players;
    public String mapName;
    private List<String> mods = new ArrayList<>();
}
