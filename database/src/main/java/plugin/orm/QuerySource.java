package plugin.orm;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import plugin.orm.sql.SqlQuery;

public interface QuerySource {

    <T> List<T> query(SqlQuery sqlQuery, RowMapper<T> mapper);

    <T> Optional<T> queryOne(SqlQuery sqlQuery, RowMapper<T> mapper);

    int execute(SqlQuery sqlQuery);

    <T> RowMapper<T> mapperFor(Class<T> type);

    default <T> CompletableFuture<List<T>> queryAsync(SqlQuery sqlQuery, RowMapper<T> mapper) {
        throw new OrmException("Async operations are not supported inside a transaction");
    }

    default <T> CompletableFuture<Optional<T>> queryOneAsync(SqlQuery sqlQuery, RowMapper<T> mapper) {
        throw new OrmException("Async operations are not supported inside a transaction");
    }

    default CompletableFuture<Integer> executeAsync(SqlQuery sqlQuery) {
        throw new OrmException("Async operations are not supported inside a transaction");
    }
}
