package plugin.orm.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import plugin.orm.OrmException;
import plugin.orm.query.DeleteQuery;
import plugin.orm.query.InsertQuery;
import plugin.orm.query.Join;
import plugin.orm.query.SelectQuery;
import plugin.orm.query.UpdateQuery;
import plugin.orm.table.Column;

public final class SqlRenderer {

    private SqlRenderer() {
    }

    public static SqlQuery select(SelectQuery query) {
        if (query.table() == null) {
            throw new OrmException("Select requires a FROM clause");
        }

        StringBuilder sql = new StringBuilder("SELECT ");
        List<Object> parameters = new ArrayList<>();

        if (query.columns().isEmpty()) {
            sql.append('*');
        } else {
            appendList(sql, query.columns(), ", ", column -> column.qualifiedName());
        }

        sql.append(" FROM ").append(query.table().name());

        for (Join join : query.joins()) {
            sql.append(" INNER JOIN ").append(join.table().name());
            if (join.on() != null) {
                sql.append(" ON ");
                join.on().appendSql(sql, parameters);
            }
        }

        if (query.where() != null) {
            sql.append(" WHERE ");
            query.where().appendSql(sql, parameters);
        }

        if (!query.orders().isEmpty()) {
            sql.append(" ORDER BY ");
            appendList(sql, query.orders(), ", ", order -> order.ascending()
                    ? order.column().qualifiedName() + " ASC"
                    : order.column().qualifiedName() + " DESC");
        }

        int limit = query.limit();
        int offset = query.offset();
        if (limit >= 0) {
            sql.append(" LIMIT ?");
            parameters.add(limit);
        }
        if (offset > 0) {
            if (limit < 0) {
                sql.append(" LIMIT -1");
            }
            sql.append(" OFFSET ?");
            parameters.add(offset);
        }

        return new SqlQuery(sql.toString(), parameters);
    }

    public static SqlQuery insert(InsertQuery query) {
        if (query.columns().isEmpty()) {
            throw new OrmException("Insert requires at least one column");
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(query.table().name()).append(" (");
        List<Object> parameters = new ArrayList<>(query.values());

        appendList(sql, query.columns(), ", ", Column::name);
        sql.append(") VALUES (");
        for (int i = 0; i < query.columns().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
        sql.append(')');

        if (query.conflictTarget() != null) {
            if (query.conflictUpdates().isEmpty()) {
                throw new OrmException("onConflictDoUpdate requires at least one update column");
            }
            sql.append(" ON CONFLICT(").append(query.conflictTarget().name()).append(") DO UPDATE SET ");
            appendList(sql, query.conflictUpdates(), ", ", column -> column.name() + " = excluded." + column.name());
        }

        return new SqlQuery(sql.toString(), parameters);
    }

    public static SqlQuery update(UpdateQuery query) {
        if (query.setColumns().isEmpty()) {
            throw new OrmException("Update requires at least one column");
        }

        StringBuilder sql = new StringBuilder("UPDATE ").append(query.table().name()).append(" SET ");
        List<Object> parameters = new ArrayList<>(query.setValues());

        appendList(sql, query.setColumns(), ", ", column -> column.name() + " = ?");

        if (query.where() != null) {
            sql.append(" WHERE ");
            query.where().appendSql(sql, parameters);
        }

        return new SqlQuery(sql.toString(), parameters);
    }

    public static SqlQuery delete(DeleteQuery query) {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(query.table().name());
        List<Object> parameters = new ArrayList<>();

        if (query.where() != null) {
            sql.append(" WHERE ");
            query.where().appendSql(sql, parameters);
        }

        return new SqlQuery(sql.toString(), parameters);
    }

    private static <T> void appendList(StringBuilder sql, List<T> items, String separator, Function<T, String> renderer) {
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sql.append(separator);
            }
            sql.append(renderer.apply(items.get(i)));
        }
    }
}
