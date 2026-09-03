package plugin.orm.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import plugin.orm.OrmException;
import plugin.orm.QuerySource;
import plugin.orm.Row;
import plugin.orm.condition.Condition;
import plugin.orm.sql.SqlQuery;
import plugin.orm.sql.SqlRenderer;
import plugin.orm.table.Column;
import plugin.orm.table.Order;
import plugin.orm.table.Table;

public final class SelectQuery {
    private final QuerySource source;
    private final List<Column<?>> columns = new ArrayList<>();
    private final List<Join> joins = new ArrayList<>();
    private final List<Order> orders = new ArrayList<>();
    private Table<?> table;
    private Condition where;
    private int limit = -1;
    private int offset;

    public SelectQuery(QuerySource source) {
        this.source = source;
    }

    public SelectQuery select(Column<?>... columns) {
        this.columns.addAll(List.of(columns));
        return this;
    }

    public SelectQuery from(Table<?> table) {
        this.table = table;
        return this;
    }

    public SelectQuery join(Table<?> table) {
        joins.add(new Join(table, null));
        return this;
    }

    public SelectQuery on(Condition condition) {
        if (joins.isEmpty()) {
            throw new OrmException("on() requires a preceding join()");
        }
        Join last = joins.remove(joins.size() - 1);
        joins.add(new Join(last.table(), condition));
        return this;
    }

    public SelectQuery where(Condition condition) {
        this.where = condition;
        return this;
    }

    public SelectQuery orderBy(Order... orders) {
        this.orders.addAll(List.of(orders));
        return this;
    }

    public SelectQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    public SelectQuery offset(int offset) {
        this.offset = offset;
        return this;
    }

    public SqlQuery toSqlQuery() {
        return SqlRenderer.select(this);
    }

    public List<Row> fetch() {
        return source.query(toSqlQuery(), Row::from);
    }

    public <T> List<T> fetch(Class<T> type) {
        return source.query(toSqlQuery(), source.mapperFor(type));
    }

    public Optional<Row> fetchOne() {
        return source.queryOne(toSqlQuery(), Row::from);
    }

    public <T> Optional<T> fetchOne(Class<T> type) {
        return source.queryOne(toSqlQuery(), source.mapperFor(type));
    }

    public CompletableFuture<List<Row>> fetchAsync() {
        return source.queryAsync(toSqlQuery(), Row::from);
    }

    public <T> CompletableFuture<List<T>> fetchAsync(Class<T> type) {
        return source.queryAsync(toSqlQuery(), source.mapperFor(type));
    }

    public CompletableFuture<Optional<Row>> fetchOneAsync() {
        return source.queryOneAsync(toSqlQuery(), Row::from);
    }

    public <T> CompletableFuture<Optional<T>> fetchOneAsync(Class<T> type) {
        return source.queryOneAsync(toSqlQuery(), source.mapperFor(type));
    }

    public List<Column<?>> columns() {
        return List.copyOf(columns);
    }

    public Table<?> table() {
        return table;
    }

    public List<Join> joins() {
        return List.copyOf(joins);
    }

    public Condition where() {
        return where;
    }

    public List<Order> orders() {
        return List.copyOf(orders);
    }

    public int limit() {
        return limit;
    }

    public int offset() {
        return offset;
    }
}
