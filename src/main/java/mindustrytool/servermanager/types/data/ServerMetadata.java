package mindustrytool.servermanager.types.data;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class ServerMetadata {
    private String serverImageHash;
    private ServerConfig config;
}
