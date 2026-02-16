package plugin.database;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.sqlite.SQLiteConfig;

import arc.util.Log;
import plugin.Control;

public class DB {
    private static final String DATABASE_DIR = "./config/database";
    private static final String DATABASE_FILE = "mindustry_tool.db";
    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static Connection connection;
    private static String databasePath;

    public static void init() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        lock.writeLock().lock();
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

            Log.info("SQLite database initialized at: " + databasePath);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SQLite database", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @FunctionalInterface
    public interface SqlHandler<T, S> {
        T accept(S statement) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer<S> {
        void accept(S statement) throws SQLException;
    }

    public static <T> T statement(SqlHandler<T, Statement> consumer) {
        try (Connection conn = getConnection(); var statement = conn.createStatement();) {
            return consumer.accept(statement);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to prepare statement", e);
        }
    }

    public static void statement(SqlConsumer<Statement> consumer) {
        try (Connection conn = getConnection(); var statement = conn.createStatement();) {
            consumer.accept(statement);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to prepare statement", e);
        }
    }

    public static <T> T prepare(String sql, SqlHandler<T, PreparedStatement> consumer) {
        try (Connection conn = getConnection(); var statement = conn.prepareStatement(sql);) {
            return consumer.accept(statement);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to prepare statement", e);
        }
    }

    public static void prepare(String sql, SqlConsumer<PreparedStatement> consumer) {
        try (Connection conn = getConnection(); var statement = conn.prepareStatement(sql);) {
            consumer.accept(statement);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to prepare statement", e);
        }
    }

    public static boolean hasRow(Statement statement, String tableName) throws SQLException {
        var result = statement.executeQuery("SELECT * FROM " + tableName + " LIMIT 1");

        return result.next();
    }

    public static boolean hasColumn(Statement statement, String tableName, String columnName) throws SQLException {
        var result = statement.executeQuery("SELECT * FROM " + tableName + " LIMIT 1");

        var metaData = result.getMetaData();
        int columnCount = metaData.getColumnCount();

        for (int i = 1; i <= columnCount; i++) {
            if (metaData.getColumnName(i).equals(columnName)) {
                return true;
            }
        }

        return false;
    }

    private static Connection getConnection() throws SQLException {
        lock.readLock().lock();
        try {
            String jdbcUrl = JDBC_URL_PREFIX + DATABASE_DIR + "/" + DATABASE_FILE; 
            var config = new SQLiteConfig();
            config.setBusyTimeout(3000);
            return connection = DriverManager.getConnection(jdbcUrl, config.toProperties());
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void close() {
        lock.writeLock().lock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                Log.info("[red]SQLite database connection closed");
            }
        } catch (Exception e) {
            Log.err("Failed to close database connection: @", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
        connection = null;

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
