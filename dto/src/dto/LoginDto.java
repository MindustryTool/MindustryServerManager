package dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LoginDto {
    String userId;
    String uuid;
    Boolean isAdmin = false;
    String name;
    @JsonIgnore
    String loginLink;
    @JsonIgnore
    JsonNode stats = new ObjectMapper().createObjectNode();
}
