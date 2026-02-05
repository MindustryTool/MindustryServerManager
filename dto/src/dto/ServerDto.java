package dto;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustry.game.Gamemode;
import mindustry.gen.Iconc;

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
    private Boolean isOfficial;
    private Boolean isHub;
    private long players;
    private String mapName;
    private String gameVersion;
    private List<String> mods = new ArrayList<>();

    public char getModeIcon() {
        try {
            var m = Gamemode.valueOf(mode.toLowerCase());
            if (m == Gamemode.survival) {
                return Iconc.modeSurvival;
            }

            if (m == Gamemode.attack) {
                return Iconc.modeAttack;
            }

            if (m == Gamemode.pvp) {
                return Iconc.modePvp;
            }

            return Iconc.info;
        } catch (Exception e) {
            return Iconc.info;
        }
    }
}
