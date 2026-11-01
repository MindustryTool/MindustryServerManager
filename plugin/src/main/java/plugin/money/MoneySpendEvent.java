package plugin.money;

import plugin.session.Session;

public class MoneySpendEvent {
    public final Session session;
    public final long amount;

    public MoneySpendEvent(Session session, long amount) {
        this.session = session;
        this.amount = amount;
    }
}
