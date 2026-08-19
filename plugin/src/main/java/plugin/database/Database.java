package plugin.database;

import java.io.File;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Enumeration;

import arc.util.Log;
import plugin.Control;
import plugin.annotations.Component;
import plugin.annotations.Destroy;
import plugin.orm.SQLiteDatabase;

@Component
public class Database {
    private static final String DATABASE_DIR = "./config/database";
    private static final String DATABASE_FILE = "mindustry_tool.db";

    private static volatile Path testPathOverride;

    private SQLiteDatabase database;

    public static void setTestPath(Path path) {
        testPathOverride = path;
    }

    public static void clearTestPath() {
        testPathOverride = null;
    }

    public SQLiteDatabase db() {
        SQLiteDatabase current = database;
        if (current == null) {
            synchronized (this) {
                if (database == null) {
                    Path override = testPathOverride;
                    String path = override != null
                            ? override.toString()
                            : new File(DATABASE_DIR, DATABASE_FILE).getAbsolutePath();
                    database = SQLiteDatabase.builder().path(path).build();
                }
                current = database;
            }
        }
        return current;
    }

    @Destroy
    public void close() {
        if (database != null) {
            try {
                database.close();
            } catch (Exception e) {
                Log.err(e);
            }
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
