package dto;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustry.gen.Player;

@Data
@Accessors(chain = true)
public class PlayerDto {
    private String name;
    private String uuid;
    private String locale;
    private String ip;
    private TeamDto team;
    private Boolean isAdmin;
    private Long joinedAt;

    private PlayerDto() {

    }

    public static PlayerDto from(Player player) {
        return new PlayerDto()//
                .setName(player.coloredName())//
                .setUuid(player.uuid())//
                .setIp(player.ip())
                .setLocale(player.locale())//
                .setIsAdmin(player.admin)//
                .setTeam(new TeamDto()//
                        .setColor(player.team().color.toString())//
                        .setName(player.team().name));
    }
}
