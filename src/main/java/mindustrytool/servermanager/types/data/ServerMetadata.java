package mindustrytool.servermanager.types.data;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class ServerMetadata extends ServerConfig {
    private String serverManagerImageHash;
    private String serverImageHash;

    public static ServerMetadata from(ServerConfig config) {
        ServerMetadata metadata = new ServerMetadata();

        metadata.setId(config.getId())
                .setName(config.getName())
                .setMode(config.getMode())
                .setHub(config.isHub())
                .setEnv(config.getEnv())
                .setPort(config.getPort())
                .setImage(config.getImage())
                .setUserId(config.getUserId())
                .setAutoTurnOff(config.isAutoTurnOff())
                .setDescription(config.getDescription())
                .setHostCommand(config.getHostCommand())
                .setPlan(config.getPlan());

        return metadata;
    }
}
