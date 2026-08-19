package plugin.orm.sql;

import java.util.List;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class TableQuery {
    private final Table<?> table;
    private final List<Column<?>> columns;

    public TableQuery(Table<?> table) {
        this(table, table.columns());
    }

    public TableQuery(Table<?> table, List<Column<?>> columns) {
        this.table = table;
        this.columns = List.copyOf(columns);
    }

    public Table<?> table() {
        return table;
    }

    public List<Column<?>> columns() {
        return columns;
    }
}