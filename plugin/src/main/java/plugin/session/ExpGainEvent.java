package plugin.session;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ExpGainEvent {
    public final Session session;
    public final long amount;
}