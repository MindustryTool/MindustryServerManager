package mindustrytool.servermanager.types.event;

import java.time.Instant;
import java.util.UUID;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@Getter
@Accessors(chain = true, fluent = true)
@RequiredArgsConstructor
public abstract class BaseEvent {
    private final UUID serverId;
    private final String name;
    private final Instant createdAt = Instant.now();
}
