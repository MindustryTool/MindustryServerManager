package plugin.database;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import arc.util.Log;
import plugin.PluginEvents;
import plugin.event.PluginUnloadEvent;

public class DB {
    private static final String DATABASE_DIR = "./database";
    private static final String DATABASE_FILE = "mindustry_tool.db";
    private static final String JDBC_URL_PREFIX = "jdbc:sqlite:";
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private static Connection connection;
    private static String databasePath;

    public static void init() {
        lock.writeLock().lock();
        try {
            if (connection != null && !connection.isClosed()) {
                return;
            }

            File databaseDir = new File(DATABASE_DIR);

            if (!databaseDir.exists()) {
                boolean created = databaseDir.mkdirs();
                if (!created) {
                    throw new DatabaseException("Failed to create database directory: " + DATABASE_DIR);
                }
            }

            File databaseFile = new File(databaseDir, DATABASE_FILE);
            databasePath = databaseFile.getAbsolutePath();

            PluginEvents.on(PluginUnloadEvent.class, event -> close());

            Log.info("SQLite database initialized at: " + databasePath);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SQLite database", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static Connection getConnection() throws SQLException {
        lock.readLock().lock();
        try {
            if (connection == null || connection.isClosed()) {
                String jdbcUrl = JDBC_URL_PREFIX + DATABASE_DIR + "/" + DATABASE_FILE;

                Log.info("Connecting to " + jdbcUrl);

                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }

                connection = DriverManager.getConnection(jdbcUrl);
            }

            return connection;
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
    }
}
