package plugin.orm;

import java.nio.file.Path;

/**
 * Test fixture: builds a SQLiteDatabase on a fresh temp-file path with the fixture schema.
 *
 * <p>In-memory SQLite (jdbc:sqlite::memory:) is intentionally NOT used: the ORM uses one
 * connection per operation, and SQLite drops in-memory databases when the last connection
 * closes. A temp file is isolated, deterministic, and exercises real connection-per-operation
 * semantics.</p>
 */
public final class TestDatabase implements AutoCloseable {
    public final SQLiteDatabase db;
    public final Path path;

    private TestDatabase(SQLiteDatabase db, Path path) {
        this.db = db;
        this.path = path;
    }

    public static TestDatabase create(Path dir) {
        Path path = dir.resolve("test.db");
        SQLiteDatabase db = SQLiteDatabase.builder().path(path.toString()).build();
        TestDatabase test = new TestDatabase(db, path);
        test.createSchema();
        return test;
    }

    private void createSchema() {
        db.raw("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY, name TEXT, active INTEGER, server_id INTEGER)");
        db.raw("CREATE TABLE IF NOT EXISTS servers (id INTEGER PRIMARY KEY, name TEXT)");
        db.raw("CREATE TABLE IF NOT EXISTS sessions (uuid TEXT PRIMARY KEY, data TEXT NOT NULL, totalExp INTEGER DEFAULT 0)");
        db.raw("CREATE TABLE IF NOT EXISTS player_logins (uuid TEXT PRIMARY KEY, last_login_date TEXT NOT NULL)");
    }

    @Override
    public void close() {
        db.close();
    }
}
