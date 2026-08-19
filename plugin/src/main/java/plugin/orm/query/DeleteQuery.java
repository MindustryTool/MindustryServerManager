package plugin.orm.query;

import java.util.concurrent.CompletableFuture;

import plugin.orm.OrmException;
import plugin.orm.QuerySource;
import plugin.orm.condition.Condition;
import plugin.orm.sql.SqlQuery;
import plugin.orm.sql.SqlRenderer;
import plugin.orm.table.Table;

public final class DeleteQuery {
    private final QuerySource source;
    private final Table<?> table;
    private Condition where;
    private boolean all;

public DeleteQuery(QuerySource source, Table<?> table) {
        this.source = source;
        this.table = table;
    }

    public DeleteQuery where(Condition condition) {
        this.where = condition;
        return this;
    }

    public DeleteQuery all() {
        this.all = true;
        return this;
    }

    public SqlQuery toSqlQuery() {
        if (where == null && !all) {
            throw new OrmException("Delete requires a where() condition or an explicit all()");
        }
        return SqlRenderer.delete(this);
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

    public Condition where() {
        return where;
    }

    public boolean isAll() {
        return all;
    }
}
