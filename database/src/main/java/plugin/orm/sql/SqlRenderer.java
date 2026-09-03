package plugin.orm.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import plugin.orm.OrmException;
import plugin.orm.SqlTypeConverter;
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

    public static SqlQuery renderTable(TableQuery query) {
        if (query.columns().isEmpty()) {
            throw new OrmException("Table definition requires at least one column");
        }

        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(query.table().name()).append(" (");
        for (int i = 0; i < query.columns().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            appendColumnDefinition(sql, query.columns().get(i));
        }
        sql.append(')');

        return new SqlQuery(sql.toString(), List.of());
    }

    private static void appendColumnDefinition(StringBuilder sql, Column<?> column) {
        sql.append(columnDefinition(column));
    }

    public static String columnDefinition(Column<?> column) {
        StringBuilder sql = new StringBuilder(column.name()).append(' ').append(SqlTypeConverter.columnTypeFor(column.type()));
        if (column.isPrimaryKey()) {
            sql.append(" PRIMARY KEY");
        }
        if (column.isNotNullConstraint()) {
            sql.append(" NOT NULL");
        }
        Object defaultValue = column.defaultValueOrNull();
        if (defaultValue != null) {
            sql.append(" DEFAULT ").append(renderLiteral(defaultValue));
        }
        return sql.toString();
    }

    private static String renderLiteral(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value.getClass().isArray()) {
            throw new OrmException("Array values cannot be used as a column DEFAULT (BLOB defaults are unsupported)");
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
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
