package plugin.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayerConnectRoomsEvent {
    private List<PlayerConnectRoom> rooms = new ArrayList<>();
}
