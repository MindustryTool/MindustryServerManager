package dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
public class ServerMetadata {
    private String serverImageHash;

    @JsonProperty(required = true)
    private ServerConfig config;
}
