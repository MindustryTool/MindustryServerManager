package plugin.event;

import lombok.Data;
import plugin.type.Session;

@Data
public class SessionRemovedEvent {
    public final Session session;
}
