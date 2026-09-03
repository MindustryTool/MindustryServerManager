package plugin.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.lang.reflect.Method;

import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.gen.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;
import plugin.session.ExpUtils;
import plugin.session.Session;
import plugin.session.SessionData;
import plugin.session.SessionRepository;
import plugin.session.SessionService;

public class ServerExpCommandTest {

    @TempDir
    static Path settingsDir;

    @TempDir
    Path tempDir;

    Database database;
    SessionRepository repository;
    SessionService sessionService;
    ServerCommands serverCommands;

    @BeforeAll
    static void initSettings() {
        Core.settings = new Settings();
        Core.settings.setDataDirectory(new Fi(settingsDir.toFile()));
    }

    @BeforeEach
    void setUp() throws Exception {
        Database.setTestPath(tempDir.resolve("test_exp.db"));
        database = new Database();
        repository = new SessionRepository(database);
        Method create = SessionRepository.class.getDeclaredMethod("createTableIfNotExists");
        create.setAccessible(true);
        create.invoke(repository);

        sessionService = new SessionService(repository);
        serverCommands = new ServerCommands(database);
    }

    @AfterEach
    void tearDown() {
        database.close();
        Database.clearTestPath();
    }

    @Test
    void offlinePlayerAddExp() {
        SessionData data = new SessionData();
        data.name = "OfflineUser";
        data.exp = 100f;
        repository.save("uuid_offline_1", data);

        serverCommands.exp("uuid_offline_1", new String[]{"+150"}, sessionService, repository);

        SessionData fresh = repository.get("uuid_offline_1");
        assertEquals(250f, fresh.exp, 0.001f);
    }

    @Test
    void offlinePlayerRemoveExp() {
        SessionData data = new SessionData();
        data.name = "OfflineUser";
        data.exp = 200f;
        repository.save("uuid_offline_2", data);

        serverCommands.exp("uuid_offline_2", new String[]{"-50"}, sessionService, repository);

        SessionData fresh = repository.get("uuid_offline_2");
        assertEquals(150f, fresh.exp, 0.001f);
    }

    @Test
    void offlinePlayerSetLevel() {
        SessionData data = new SessionData();
        data.name = "OfflineUser";
        data.exp = 0f;
        repository.save("uuid_offline_3", data);

        serverCommands.exp("uuid_offline_3", new String[]{"5L"}, sessionService, repository);

        SessionData fresh = repository.get("uuid_offline_3");
        assertEquals((float) ExpUtils.totalExpForLevel(5), fresh.exp, 0.001f);
    }

    @Test
    void offlinePlayerAddLevelWithLowercaseL() {
        SessionData data = new SessionData();
        data.name = "OfflineUser";
        data.exp = (float) ExpUtils.totalExpForLevel(2);
        repository.save("uuid_offline_4", data);

        serverCommands.exp("uuid_offline_4", new String[]{"+3l"}, sessionService, repository);

        SessionData fresh = repository.get("uuid_offline_4");
        assertEquals((float) ExpUtils.totalExpForLevel(5), fresh.exp, 0.001f);
    }

    @Test
    void offlinePlayerRemoveLevelClampsAtLevel1() {
        SessionData data = new SessionData();
        data.name = "OfflineUser";
        data.exp = (float) ExpUtils.totalExpForLevel(2);
        repository.save("uuid_offline_5", data);

        serverCommands.exp("uuid_offline_5", new String[]{"-10L"}, sessionService, repository);

        SessionData fresh = repository.get("uuid_offline_5");
        assertEquals(0f, fresh.exp, 0.001f);
    }

    @Test
    void unknownPlayerLogsErrorAndDoesNotCreateRow() {
        serverCommands.exp("missing_uuid", new String[]{"+100"}, sessionService, repository);

        assertFalse(repository.exists("missing_uuid"));
    }

    @Test
    void invalidAmountDoesNotMutateExp() {
        SessionData data = new SessionData();
        data.name = "OfflineUser";
        data.exp = 100f;
        repository.save("uuid_offline_6", data);

        serverCommands.exp("uuid_offline_6", new String[]{"invalid"}, sessionService, repository);

        SessionData fresh = repository.get("uuid_offline_6");
        assertEquals(100f, fresh.exp, 0.001f);
    }

    @Test
    void onlinePlayerExpUpdate() {
        Player player = Player.create();
        player.name = "OnlineHero";
        player.locale = "en";
        // Mock UUID via reflection/field if needed
        try {
            var field = player.getClass().getField("uuid");
            field.set(player, "uuid_online_1");
        } catch (Exception e) {
            // Player in Mindustry might have player.con or usid/uuid method
            // Check if player.uuid() returns a value
        }

        SessionData data = new SessionData();
        data.name = "OnlineHero";
        data.exp = 50f;
        Session session = new Session(player, data);
        sessionService.get().put(player.uuid(), session);

        serverCommands.exp(player.uuid(), new String[]{"+50"}, sessionService, repository);

        assertEquals(100f, data.exp, 0.001f);
    }
}
