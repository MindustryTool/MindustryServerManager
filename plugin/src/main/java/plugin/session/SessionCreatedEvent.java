package plugin.session;

import lombok.Data;

@Data
public class SessionCreatedEvent {
    public final Session session;
}
