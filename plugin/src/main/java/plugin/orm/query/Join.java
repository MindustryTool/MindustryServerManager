package plugin.orm.query;

import plugin.orm.condition.Condition;
import plugin.orm.table.Table;

public final class Join {
    private final Table<?> table;
    private final Condition on;

    Join(Table<?> table, Condition on) {
        this.table = table;
        this.on = on;
    }

    public Table<?> table() {
        return table;
    }

    public Condition on() {
        return on;
    }
}
