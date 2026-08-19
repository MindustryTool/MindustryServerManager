package plugin.database;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.sqlite.SQLiteConfig;

import arc.util.Log;
import plugin.Control;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.annotations.Init;

@Component
public class Database {
    private static final String DATABASE_DIR = "./config/database";
    private static final String DATABASE_FILE = "mindustry_tool.db";
    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";

    private boolean isInitialized = false;
    private String databasePath;
    private Connection connection;
    private ExecutorService executor;

    @Init
    public void init() {
        if (isInitialized && connection != null) {
            return;
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        try {
            File databaseDir = new File(DATABASE_DIR);

            if (!databaseDir.exists()) {
                boolean created = databaseDir.mkdirs();
                if (!created) {
                    throw new DatabaseException("Failed to create database directory: " + DATABASE_DIR);
                }
            }

            File databaseFile = new File(databaseDir, DATABASE_FILE);
            databasePath = databaseFile.getAbsolutePath();

            String jdbcUrl = JDBC_URL_PREFIX + DATABASE_DIR + "/" + DATABASE_FILE;
            var config = new SQLiteConfig();
            config.setBusyTimeout(3000);
            connection = DriverManager.getConnection(jdbcUrl, config.toProperties());

            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadExecutor();
            }

            isInitialized = true;
            Log.info("SQLite database initialized at: " + databasePath);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SQLite database", e);
        }
    }

    public synchronized Connection getConnection() {
        if (!isInitialized || connection == null) {
            init();
        }
        return connection;
    }

    @FunctionalInterface
    public interface SqlHandler<T, S> {
        T accept(S statement) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer<S> {
        void accept(S statement) throws SQLException;
    }

    public <T> T statement(SqlHandler<T, Statement> consumer) {
        return execute(() -> {
            try (var statement = connection.createStatement()) {
                return consumer.accept(statement);
            } catch (SQLException e) {
                throw new DatabaseException("Failed to execute statement", e);
            }
        });
    }

    public void statement(SqlConsumer<Statement> consumer) {
        execute(() -> {
            try (var statement = connection.createStatement()) {
                consumer.accept(statement);
            } catch (SQLException e) {
                throw new DatabaseException("Failed to execute statement", e);
            }
            return null;
        });
    }

    public <T> T prepare(String sql, SqlHandler<T, PreparedStatement> consumer) {
        return execute(() -> {
            try (var statement = connection.prepareStatement(sql)) {
                return consumer.accept(statement);
            } catch (SQLException e) {
                throw new DatabaseException("Failed to prepare statement", e);
            }
        });
    }

    public void prepare(String sql, SqlConsumer<PreparedStatement> consumer) {
        execute(() -> {
            try (var statement = connection.prepareStatement(sql)) {
                consumer.accept(statement);
            } catch (SQLException e) {
                throw new DatabaseException("Failed to prepare statement", e);
            }
            return null;
        });
    }

    public boolean hasRow(Statement statement, String tableName) throws SQLException {
        try (var result = statement.executeQuery("SELECT * FROM " + tableName + " LIMIT 1")) {
            return result.next();
        }
    }

    public boolean hasColumn(Statement statement, String tableName, String columnName) throws SQLException {
        try (var result = statement.executeQuery("SELECT * FROM " + tableName + " LIMIT 1")) {
            var metaData = result.getMetaData();
            int columnCount = metaData.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                if (metaData.getColumnName(i).equals(columnName)) {
                    return true;
                }
            }

            return false;
        }
    }

    private <T> T execute(Callable<T> task) {
        init();
        try {
            return executor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DatabaseException("Database operation interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new DatabaseException("Failed to execute database operation", cause);
        }
    }

    @Destroy
    public void close() {
        executor.shutdown();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            Log.err(e);
        }

        Enumeration<Driver> drivers = DriverManager.getDrivers();
        ClassLoader pluginClassLoader = Control.class.getClassLoader();

        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == pluginClassLoader) {
                try {
                    DriverManager.deregisterDriver(driver);
                } catch (SQLException e) {
                    Log.err(e);
                }
            }
        }
    }
}
