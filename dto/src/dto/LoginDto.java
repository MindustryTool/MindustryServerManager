package dto;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class LoginDto {
    String uuid;
    Boolean isAdmin;
    String name;
    String loginLink;
    JsonNode stats;
}
