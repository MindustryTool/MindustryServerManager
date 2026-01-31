package dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LoginDto {
    String uuid;
    Boolean isAdmin = false;
    String name;
    String loginLink;
    JsonNode stats = new ObjectMapper().createObjectNode();
}
