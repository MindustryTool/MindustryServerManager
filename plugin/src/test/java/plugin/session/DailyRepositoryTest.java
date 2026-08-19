package plugin.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;

public class DailyRepositoryTest {

    @TempDir
    Path tempDir;

    Database database;
    DailyRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        Database.setTestPath(tempDir.resolve("daily.db"));
        database = new Database();
        repository = new DailyRepository(database);
        Method create = DailyRepository.class.getDeclaredMethod("createTableIfNotExists");
        create.setAccessible(true);
        create.invoke(repository);
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
    }

    @Test
    void getLastLoginReturnsEmptyForUnknownUuid() {
        assertFalse(repository.getLastLogin("missing").isPresent());
    }

    @Test
    void setLastLoginThenGetReturnsDate() {
        repository.setLastLogin("u1", "2026-08-19");

        assertTrue(repository.getLastLogin("u1").isPresent());
        assertEquals("2026-08-19", repository.getLastLogin("u1").get());
    }

    @Test
    void setLastLoginOverwritesExistingRow() {
        repository.setLastLogin("u1", "2026-08-18");
        repository.setLastLogin("u1", "2026-08-19");

        assertEquals(1, database.db().rawQuery("SELECT COUNT(*) AS c FROM player_logins").get(0).getInt("c"));
        assertEquals("2026-08-19", repository.getLastLogin("u1").get());
    }

    @Test
    void rowsAreStoredPerUuid() {
        repository.setLastLogin("u1", "2026-08-18");
        repository.setLastLogin("u2", "2026-08-19");

        assertEquals("2026-08-18", repository.getLastLogin("u1").get());
        assertEquals("2026-08-19", repository.getLastLogin("u2").get());
    }

    @Test
    void createTableIsIdempotent() throws Exception {
        Method create = DailyRepository.class.getDeclaredMethod("createTableIfNotExists");
        create.setAccessible(true);
        create.invoke(repository);
        create.invoke(repository);

        repository.setLastLogin("u1", "2026-08-19");
        assertTrue(repository.getLastLogin("u1").isPresent());
    }
}
