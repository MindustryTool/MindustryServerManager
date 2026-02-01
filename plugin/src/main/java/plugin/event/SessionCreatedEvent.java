package plugin.event;

import lombok.Data;
import plugin.type.Session;

@Data
public class SessionCreatedEvent {
    public final Session session;
}
