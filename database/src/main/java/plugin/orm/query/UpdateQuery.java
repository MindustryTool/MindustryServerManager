package plugin.orm.query;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import plugin.orm.QuerySource;
import plugin.orm.condition.Condition;
import plugin.orm.sql.SqlQuery;
import plugin.orm.sql.SqlRenderer;
import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class UpdateQuery {
    private final QuerySource source;
    private final Table<?> table;
    private final List<Column<?>> setColumns = new ArrayList<>();
    private final List<Object> setValues = new ArrayList<>();
    private Condition where;

public UpdateQuery(QuerySource source, Table<?> table) {
        this.source = source;
        this.table = table;
    }

    public UpdateQuery set(Column<?> column, Object value) {
        setColumns.add(column);
        setValues.add(value);
        return this;
    }

    public UpdateQuery where(Condition condition) {
        this.where = condition;
        return this;
    }

    public SqlQuery toSqlQuery() {
        return SqlRenderer.update(this);
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

    public List<Column<?>> setColumns() {
        return List.copyOf(setColumns);
    }

    public List<Object> setValues() {
        return List.copyOf(setValues);
    }

    public Condition where() {
        return where;
    }
}
