package dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class ServerDto {

    private List<ServerResponseData> servers = new ArrayList<>();

}
