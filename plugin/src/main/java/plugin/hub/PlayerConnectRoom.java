package plugin.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayerConnectRoom {
    private String roomId;
    private RoomData data;
    private String link;
    private String address;
    private String httpAddress;
    private String name;

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RoomData {
        private String name;
        private String status;
        private boolean isPrivate;
        private boolean isSecured;
        private String mapName;
        private String gamemode;
        private List<String> mods = new ArrayList<>();
        private String version;
        private String modVersion;
        private String locale;
        private long createdAt;
        private int ping;
        private List<PlayerInfo> players = new ArrayList<>();
        private String protocolVersion;
    }

    @Data
    @Accessors(chain = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlayerInfo {
        private String name;
        private String locale;
    }
}
