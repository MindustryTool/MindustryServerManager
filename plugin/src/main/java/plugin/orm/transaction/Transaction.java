package plugin.orm.transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import plugin.orm.OrmException;
import plugin.orm.QuerySource;
import plugin.orm.RowMapper;
import plugin.orm.SQLiteDatabase;
import plugin.orm.query.DeleteQuery;
import plugin.orm.query.InsertQuery;
import plugin.orm.query.SelectQuery;
import plugin.orm.query.UpdateQuery;
import plugin.orm.sql.SqlQuery;
import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class Transaction implements QuerySource {
    private final Connection connection;
    private final SQLiteDatabase database;

public Transaction(Connection connection, SQLiteDatabase database) {
        this.connection = connection;
        this.database = database;
    }

    public SelectQuery select(Column<?>... columns) {
        return new SelectQuery(this).select(columns);
    }

    public InsertQuery insert(Table<?> table) {
        return new InsertQuery(this, table);
    }

    public UpdateQuery update(Table<?> table) {
        return new UpdateQuery(this, table);
    }

    public DeleteQuery delete(Table<?> table) {
        return new DeleteQuery(this, table);
    }

    @Override
    public <T> List<T> query(SqlQuery sqlQuery, RowMapper<T> mapper) {
        try {
            return SQLiteDatabase.queryWith(connection, sqlQuery, mapper);
        } catch (SQLException e) {
            throw new OrmException("Failed to execute query in transaction: " + sqlQuery.sql(), e);
        }
    }

    @Override
    public <T> Optional<T> queryOne(SqlQuery sqlQuery, RowMapper<T> mapper) {
        try {
            return SQLiteDatabase.queryOneWith(connection, sqlQuery, mapper);
        } catch (SQLException e) {
            throw new OrmException("Failed to execute query in transaction: " + sqlQuery.sql(), e);
        }
    }

    @Override
    public int execute(SqlQuery sqlQuery) {
        try {
            return SQLiteDatabase.executeWith(connection, sqlQuery);
        } catch (SQLException e) {
            throw new OrmException("Failed to execute statement in transaction: " + sqlQuery.sql(), e);
        }
    }

    @Override
    public <T> RowMapper<T> mapperFor(Class<T> type) {
        return database.mapperFor(type);
    }
}
