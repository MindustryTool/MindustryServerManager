package plugin.session;

import lombok.Data;

@Data
public class SessionRemovedEvent {
    public final Session session;
}
