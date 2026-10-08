package common.player;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class Login {
    String userId;
    String uuid;
    Boolean isAdmin = false;
    String name;
    String loginLink;
    JsonNode stats = new ObjectMapper().createObjectNode();
}
