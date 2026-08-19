package plugin.orm;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.sqlite.SQLiteConfig;

import plugin.orm.query.DeleteQuery;
import plugin.orm.query.InsertQuery;
import plugin.orm.query.SelectQuery;
import plugin.orm.query.UpdateQuery;
import plugin.orm.sql.SqlQuery;
import plugin.orm.sql.SqlRenderer;
import plugin.orm.sql.TableQuery;
import plugin.orm.table.Column;
import plugin.orm.table.Table;
import plugin.orm.transaction.Transaction;

public final class SQLiteDatabase implements QuerySource, AutoCloseable {

    private static final String DRIVER = "org.sqlite.JDBC";
    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final ThreadLocal<Boolean> IN_TRANSACTION = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final String path;
    private final ExecutorService externalExecutor;
    private final int busyTimeoutMs;

    private final Object initLock = new Object();
    private final ConcurrentHashMap<Class<?>, RowMapper<?>> mappers = new ConcurrentHashMap<>();

    private volatile boolean initialized;
    private volatile boolean closed;
    private int initializationCount;
    private ExecutorService ownedExecutor;

    private SQLiteDatabase(Builder builder) {
        this.path = builder.path;
        this.externalExecutor = builder.executor;
        this.busyTimeoutMs = builder.busyTimeoutMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String path;
        private ExecutorService executor;
        private int busyTimeoutMs = 3000;

        private Builder() {
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public Builder busyTimeoutMs(int busyTimeoutMs) {
            this.busyTimeoutMs = busyTimeoutMs;
            return this;
        }

        public SQLiteDatabase build() {
            if (path == null || path.isBlank()) {
                throw new OrmException("Database path is required");
            }
            return new SQLiteDatabase(this);
        }
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

    public void createTableIfNotExists(Table<?> table) {
        createTableIfNotExists(table, table.columns().toArray(new Column<?>[0]));
    }

    public void createTableIfNotExists(Table<?> table, Column<?>... columns) {
        SqlQuery ddl = SqlRenderer.renderTable(new TableQuery(table, List.of(columns)));
        ensureOpen();
        try (Connection connection = acquireConnection()) {
            executeWith(connection, ddl);
        } catch (SQLException e) {
            throw new OrmException("Failed to create table: " + ddl.sql(), e);
        }
    }

    public CompletableFuture<Void> createTableIfNotExistsAsync(Table<?> table) {
        return createTableIfNotExistsAsync(table, table.columns().toArray(new Column<?>[0]));
    }

    public CompletableFuture<Void> createTableIfNotExistsAsync(Table<?> table, Column<?>... columns) {
        ExecutorService executor = executorForAsync();
        if (executor == null) {
            return CompletableFuture.failedFuture(new OrmException("Database is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            createTableIfNotExists(table, columns);
            return null;
        }, executor);
    }

    public void addColumnIfMissing(Table<?> table, Column<?> column) {
        if (hasColumn(table.name(), column.name())) {
            return;
        }
        String ddl = "ALTER TABLE " + table.name() + " ADD COLUMN " + SqlRenderer.columnDefinition(column);
        ensureOpen();
        try (Connection connection = acquireConnection();
                PreparedStatement statement = connection.prepareStatement(ddl)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new OrmException("Failed to add column: " + ddl, e);
        }
    }

    public CompletableFuture<Void> addColumnIfMissingAsync(Table<?> table, Column<?> column) {
        ExecutorService executor = executorForAsync();
        if (executor == null) {
            return CompletableFuture.failedFuture(new OrmException("Database is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            addColumnIfMissing(table, column);
            return null;
        }, executor);
    }

    public <T> void registerMapper(Class<T> type, RowMapper<T> mapper) {
        mappers.put(type, mapper);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> RowMapper<T> mapperFor(Class<T> type) {
        RowMapper<?> mapper = mappers.get(type);
        if (mapper == null) {
            throw new OrmException("No mapper registered for " + type.getName());
        }
        return (RowMapper<T>) mapper;
    }

    @Override
    public <T> List<T> query(SqlQuery sqlQuery, RowMapper<T> mapper) {
        ensureOpen();
        try (Connection connection = acquireConnection()) {
            return queryWith(connection, sqlQuery, mapper);
        } catch (SQLException e) {
            throw new OrmException("Failed to execute query: " + sqlQuery.sql(), e);
        }
    }

    @Override
    public <T> Optional<T> queryOne(SqlQuery sqlQuery, RowMapper<T> mapper) {
        ensureOpen();
        try (Connection connection = acquireConnection()) {
            return queryOneWith(connection, sqlQuery, mapper);
        } catch (SQLException e) {
            throw new OrmException("Failed to execute query: " + sqlQuery.sql(), e);
        }
    }

    @Override
    public int execute(SqlQuery sqlQuery) {
        ensureOpen();
        try (Connection connection = acquireConnection()) {
            return executeWith(connection, sqlQuery);
        } catch (SQLException e) {
            throw new OrmException("Failed to execute statement: " + sqlQuery.sql(), e);
        }
    }

    @Override
    public <T> CompletableFuture<List<T>> queryAsync(SqlQuery sqlQuery, RowMapper<T> mapper) {
        ExecutorService executor = executorForAsync();
        if (executor == null) {
            return CompletableFuture.failedFuture(new OrmException("Database is closed"));
        }
        return CompletableFuture.supplyAsync(() -> query(sqlQuery, mapper), executor);
    }

    @Override
    public <T> CompletableFuture<Optional<T>> queryOneAsync(SqlQuery sqlQuery, RowMapper<T> mapper) {
        ExecutorService executor = executorForAsync();
        if (executor == null) {
            return CompletableFuture.failedFuture(new OrmException("Database is closed"));
        }
        return CompletableFuture.supplyAsync(() -> queryOne(sqlQuery, mapper), executor);
    }

    @Override
    public CompletableFuture<Integer> executeAsync(SqlQuery sqlQuery) {
        ExecutorService executor = executorForAsync();
        if (executor == null) {
            return CompletableFuture.failedFuture(new OrmException("Database is closed"));
        }
        return CompletableFuture.supplyAsync(() -> execute(sqlQuery), executor);
    }

    public void transaction(Consumer<Transaction> body) {
        if (IN_TRANSACTION.get()) {
            throw new OrmException("Nested transactions are not supported");
        }
        ensureOpen();
        try (Connection connection = acquireConnection()) {
            IN_TRANSACTION.set(Boolean.TRUE);
            try {
                connection.setAutoCommit(false);
                Transaction transaction = new Transaction(connection, this);
                body.accept(transaction);
                connection.commit();
            } catch (Throwable t) {
                try {
                    connection.rollback();
                } catch (SQLException e) {
                    t.addSuppressed(e);
                }
                if (t instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (t instanceof Error error) {
                    throw error;
                }
                throw new OrmException(t);
            } finally {
                IN_TRANSACTION.remove();
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            throw new OrmException("Transaction failed", e);
        }
    }

    public CompletableFuture<Void> transactionAsync(Consumer<Transaction> body) {
        ExecutorService executor = executorForAsync();
        if (executor == null) {
            return CompletableFuture.failedFuture(new OrmException("Database is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            transaction(body);
            return null;
        }, executor);
    }

    public int raw(String sql, Object... parameters) {
        ensureOpen();
        try (Connection connection = acquireConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            SqlTypeConverter.bindAll(statement, List.of(parameters));
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new OrmException("Failed to execute raw statement: " + sql, e);
        }
    }

    public List<Row> rawQuery(String sql, Object... parameters) {
        ensureOpen();
        try (Connection connection = acquireConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            SqlTypeConverter.bindAll(statement, List.of(parameters));
            try (ResultSet rs = statement.executeQuery()) {
                List<Row> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(Row.from(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new OrmException("Failed to execute raw query: " + sql, e);
        }
    }

    public RawResult rawExecute(String sql, Object... parameters) {
        ensureOpen();
        try (Connection connection = acquireConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            SqlTypeConverter.bindAll(statement, List.of(parameters));
            boolean hasResultSet = statement.execute();
            if (hasResultSet) {
                try (ResultSet rs = statement.getResultSet()) {
                    List<Row> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(Row.from(rs));
                    }
                    return RawResult.rows(rows);
                }
            }
            return RawResult.updated(statement.getUpdateCount());
        } catch (SQLException e) {
            throw new OrmException("Failed to execute raw statement: " + sql, e);
        }
    }

    public boolean hasColumn(String table, String column) {
        ensureOpen();
        try (Connection connection = acquireConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " LIMIT 1")) {
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                for (int i = 1; i <= metaData.getColumnCount(); i++) {
                    if (metaData.getColumnName(i).equals(column)) {
                        return true;
                    }
                }
                return false;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        synchronized (initLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (ownedExecutor != null) {
                ownedExecutor.shutdown();
                ownedExecutor = null;
            }
        }
    }

    public String path() {
        return path;
    }

    int initializationCount() {
        return initializationCount;
    }

    boolean isInitialized() {
        return initialized;
    }

    ExecutorService ownedExecutorOrNull() {
        return ownedExecutor;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (initLock) {
            if (!initialized) {
                ensureOpen();
                try {
                    Class.forName(DRIVER);
                    DriverManager.registerDriver(new org.sqlite.JDBC());
                } catch (ClassNotFoundException e) {
                    throw new OrmException("SQLite JDBC driver not found", e);
                } catch (SQLException e) {
                    throw new OrmException("Failed to register SQLite JDBC driver", e);
                }
                File parent = new File(path).getAbsoluteFile().getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new OrmException("Failed to create database directory: " + parent);
                }
                if (externalExecutor == null && ownedExecutor == null) {
                    ownedExecutor = Executors.newFixedThreadPool(2, runnable -> new Thread(runnable, "orm-db"));
                }
                initializationCount++;
                initialized = true;
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new OrmException("Database is closed");
        }
    }

    private ExecutorService executorForAsync() {
        if (closed) {
            return null;
        }
        if (externalExecutor != null) {
            return externalExecutor;
        }
        ExecutorService executor = ownedExecutor;
        if (executor != null) {
            return executor;
        }
        synchronized (initLock) {
            executor = ownedExecutor;
            if (executor == null) {
                if (closed) {
                    return null;
                }
                executor = Executors.newFixedThreadPool(2, runnable -> new Thread(runnable, "orm-db"));
                ownedExecutor = executor;
            }
            return executor;
        }
    }

    private Connection acquireConnection() throws SQLException {
        ensureInitialized();
        ensureOpen();
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(busyTimeoutMs);
        return DriverManager.getConnection(JDBC_PREFIX + path, config.toProperties());
    }

    public static <T> List<T> queryWith(Connection connection, SqlQuery sqlQuery, RowMapper<T> mapper) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sqlQuery.sql())) {
            SqlTypeConverter.bindAll(statement, sqlQuery.parameters());
            try (ResultSet rs = statement.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
                return result;
            }
        }
    }

    public static <T> Optional<T> queryOneWith(Connection connection, SqlQuery sqlQuery, RowMapper<T> mapper)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sqlQuery.sql())) {
            SqlTypeConverter.bindAll(statement, sqlQuery.parameters());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                T first = mapper.map(rs);
                if (rs.next()) {
                    throw new OrmException("Query returned more than one row");
                }
                return Optional.of(first);
            }
        }
    }

    public static int executeWith(Connection connection, SqlQuery sqlQuery) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sqlQuery.sql())) {
            SqlTypeConverter.bindAll(statement, sqlQuery.parameters());
            return statement.executeUpdate();
        }
    }

    public static final class RawResult {
        private final List<Row> rows;
        private final int updateCount;

        private RawResult(List<Row> rows, int updateCount) {
            this.rows = rows;
            this.updateCount = updateCount;
        }

        static RawResult rows(List<Row> rows) {
            return new RawResult(rows, -1);
        }

        static RawResult updated(int updateCount) {
            return new RawResult(null, updateCount);
        }

        public boolean hasRows() {
            return rows != null;
        }

        public List<Row> rows() {
            return rows;
        }

        public int updateCount() {
            return updateCount;
        }
    }
}
