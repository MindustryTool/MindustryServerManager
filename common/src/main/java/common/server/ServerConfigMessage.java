package dto;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerConfigDto {
    String jwt;
    StartServerDto startServer;
}
