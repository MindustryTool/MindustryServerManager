package plugin.money;

import plugin.session.Session;

public class MoneyGainEvent {
    public final Session session;
    public final long amount;

    public MoneyGainEvent(Session session, long amount) {
        this.session = session;
        this.amount = amount;
    }
}
