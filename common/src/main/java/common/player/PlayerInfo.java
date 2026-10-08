package common.player;

import lombok.Data;
import lombok.experimental.Accessors;
import mindustry.gen.Player;

@Data
@Accessors(chain = true)
public class PlayerInfo {
    private String name;
    private String uuid;
    private String locale;
    private String ip;
    private TeamInfo team;
    private Boolean isAdmin;
    private Long joinedAt;
    private Login login;

    private PlayerInfo() {

    }

    public static PlayerInfo from(Player player, Login login) {
        return new PlayerInfo()//
                .setName(player.coloredName())//
                .setUuid(player.uuid())//
                .setIp(player.ip())
                .setLocale(player.locale())//
                .setIsAdmin(player.admin)//
                .setTeam(new TeamInfo()//
                        .setColor(player.team().color.toString())//
                        .setName(player.team().name))//
                .setLogin(login);
    }
}
