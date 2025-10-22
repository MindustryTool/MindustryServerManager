package mindustrytool.servermanager.types.event;

import java.time.Instant;

import lombok.Getter;

@Getter
public abstract class ServerEvent {
    private String name;
    private Instant createdAt;
}
