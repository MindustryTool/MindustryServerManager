package plugin.orm.query;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import plugin.orm.QuerySource;
import plugin.orm.sql.SqlQuery;
import plugin.orm.sql.SqlRenderer;
import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class InsertQuery {
    private final QuerySource source;
    private final Table<?> table;
    private final List<Column<?>> columns = new ArrayList<>();
    private final List<Object> values = new ArrayList<>();
    private Column<?> conflictTarget;
    private final List<Column<?>> conflictUpdates = new ArrayList<>();

public InsertQuery(QuerySource source, Table<?> table) {
        this.source = source;
        this.table = table;
    }

    public InsertQuery set(Column<?> column, Object value) {
        columns.add(column);
        values.add(value);
        return this;
    }

    public InsertQuery onConflictDoUpdate(Column<?> conflictTarget, Column<?>... updateColumns) {
        this.conflictTarget = conflictTarget;
        this.conflictUpdates.addAll(List.of(updateColumns));
        return this;
    }

    public SqlQuery toSqlQuery() {
        return SqlRenderer.insert(this);
    }

    public int execute() {
        return source.execute(toSqlQuery());
    }

    public CompletableFuture<Integer> executeAsync() {
        return source.executeAsync(toSqlQuery());
    }

    public Table<?> table() {
        return table;
    }

    public List<Column<?>> columns() {
        return List.copyOf(columns);
    }

    public List<Object> values() {
        return List.copyOf(values);
    }

    public Column<?> conflictTarget() {
        return conflictTarget;
    }

    public List<Column<?>> conflictUpdates() {
        return List.copyOf(conflictUpdates);
    }
}
