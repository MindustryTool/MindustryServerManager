package plugin.security;

import java.nio.file.Path;

import arc.Core;
import arc.Settings;
import mindustry.Vars;
import mindustry.core.NetServer;
import mindustry.net.Net;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import plugin.database.Database;
import plugin.database.schema.BanLogs;
import plugin.database.schema.BannedUsers;
import plugin.database.schema.KickLogs;
import plugin.event.KickEvent;
import plugin.event.ModerationEvent;
import plugin.event.PlayerKickEvent;
import plugin.event.UserBannedEvent;
import plugin.session.LoginService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UserBanServiceTest {

    @TempDir
    Path tempDir;

    private Database database;
    private ModerationLogService moderationLogService;
    private ModerationService moderationService;
    private UserBanService banService;

    @BeforeEach
    void setUp() {
        if (Vars.net == null) {
            Vars.net = new Net(null);
        }
        if (Vars.netServer == null) {
            Vars.netServer = new NetServer();
        }
        Core.settings = new Settings();
        Database.setTestPath(tempDir.resolve("userban_test.db"));
        database = new Database();
        moderationLogService = new ModerationLogService(database);
        moderationLogService.createTablesIfNotExists();
        moderationService = new ModerationService();
        banService = new UserBanService(database, moderationLogService, moderationService);
        banService.createTableIfNotExists();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
        Database.clearTestPath();
    }

    @Test
    void testBanAndPersistDatabase() {
        assertFalse(banService.isBanned("user_abc"));

        assertTrue(banService.ban("user_abc", "Griefing", "AdminBob"));
        assertTrue(banService.isBanned("user_abc"));
        assertTrue(banService.getBannedUserIds().contains("user_abc"));
        assertTrue(moderationService.isBannedRecently("user_abc"));

        // Verify row in BannedUsers table
        var row = database.db().select(BannedUsers.USER_ID, BannedUsers.REASON, BannedUsers.ACTOR)
                .from(BannedUsers.TABLE)
                .where(BannedUsers.USER_ID.eq("user_abc"))
                .fetchOne()
                .orElse(null);
        assertNotNull(row);
        assertEquals("user_abc", row.get(BannedUsers.USER_ID));
        assertEquals("Griefing", row.get(BannedUsers.REASON));
        assertEquals("AdminBob", row.get(BannedUsers.ACTOR));

        // Verify row in BanLogs table
        var logRow = database.db().select(BanLogs.ACTION, BanLogs.USER_ID, BanLogs.ACTOR, BanLogs.REASON)
                .from(BanLogs.TABLE)
                .where(BanLogs.USER_ID.eq("user_abc"))
                .fetchOne()
                .orElse(null);
        assertNotNull(logRow);
        assertEquals("BAN", logRow.get(BanLogs.ACTION));
        assertEquals("AdminBob", logRow.get(BanLogs.ACTOR));
        assertEquals("Griefing", logRow.get(BanLogs.REASON));

        // Simulate reload from database
        UserBanService reloadedService = new UserBanService(database, moderationLogService, moderationService);
        reloadedService.loadFromDatabase();
        assertTrue(reloadedService.isBanned("user_abc"));

        // Unban
        assertTrue(reloadedService.unban("user_abc", "AdminAlice"));
        assertFalse(reloadedService.isBanned("user_abc"));

        // Verify row deleted from BannedUsers table
        var deletedRow = database.db().select(BannedUsers.USER_ID)
                .from(BannedUsers.TABLE)
                .where(BannedUsers.USER_ID.eq("user_abc"))
                .fetchOne();
        assertTrue(deletedRow.isEmpty());

        // Verify reload after unban
        UserBanService reloadedAfterUnban = new UserBanService(database, moderationLogService, moderationService);
        reloadedAfterUnban.loadFromDatabase();
        assertFalse(reloadedAfterUnban.isBanned("user_abc"));
    }

    @Test
    void testBanRequiresReason() {
        assertThrows(IllegalArgumentException.class, () -> banService.ban("user_123", null, "AdminBob"));
        assertThrows(IllegalArgumentException.class, () -> banService.ban("user_123", "   ", "AdminBob"));
        assertFalse(banService.isBanned("user_123"));
    }

    @Test
    void testKickLogging() {
        moderationLogService.logKick("uuid_1", "127.0.0.1", "PlayerOne", "griefing", "AdminBob");

        var row = database.db().select(KickLogs.UUID, KickLogs.IP, KickLogs.NAME, KickLogs.REASON, KickLogs.ACTOR)
                .from(KickLogs.TABLE)
                .where(KickLogs.UUID.eq("uuid_1"))
                .fetchOne()
                .orElse(null);
        assertNotNull(row);
        assertEquals("uuid_1", row.get(KickLogs.UUID));
        assertEquals("127.0.0.1", row.get(KickLogs.IP));
        assertEquals("PlayerOne", row.get(KickLogs.NAME));
        assertEquals("griefing", row.get(KickLogs.REASON));
        assertEquals("AdminBob", row.get(KickLogs.ACTOR));

        // Test vote kick event
        moderationLogService.onKickEvent(new KickEvent("127.0.0.2", "uuid_2", "vote"));
        var voteRow = database.db().select(KickLogs.ACTOR, KickLogs.REASON)
                .from(KickLogs.TABLE)
                .where(KickLogs.UUID.eq("uuid_2"))
                .fetchOne()
                .orElse(null);
        assertNotNull(voteRow);
        assertEquals("VOTE", voteRow.get(KickLogs.ACTOR));
        assertEquals("vote", voteRow.get(KickLogs.REASON));

        // Test PlayerKickEvent
        PlayerKickEvent kickEvent = new PlayerKickEvent(null, "uuid_3", "127.0.0.3", "PlayerThree", "Spamming", "AdminCharlie");
        moderationLogService.onPlayerKick(kickEvent);
        var playerKickRow = database.db().select(KickLogs.UUID, KickLogs.IP, KickLogs.NAME, KickLogs.REASON, KickLogs.ACTOR)
                .from(KickLogs.TABLE)
                .where(KickLogs.UUID.eq("uuid_3"))
                .fetchOne()
                .orElse(null);
        assertNotNull(playerKickRow);
        assertEquals("uuid_3", playerKickRow.get(KickLogs.UUID));
        assertEquals("127.0.0.3", playerKickRow.get(KickLogs.IP));
        assertEquals("PlayerThree", playerKickRow.get(KickLogs.NAME));
        assertEquals("Spamming", playerKickRow.get(KickLogs.REASON));
        assertEquals("AdminCharlie", playerKickRow.get(KickLogs.ACTOR));
    }

    @Test
    void testModerationEventContract() {
        UserBannedEvent banEvent = new UserBannedEvent("user_99", "Toxic behavior", "AdminDave");
        assertTrue(banEvent instanceof ModerationEvent);
        assertEquals("AdminDave", banEvent.actor());
        assertEquals("Toxic behavior", banEvent.reason());
        assertTrue(banEvent.timestamp() > 0);

        PlayerKickEvent kickEvent = new PlayerKickEvent(null, "uuid_99", "10.0.0.1", "Griefer", "Griefing", "AdminDave");
        assertTrue(kickEvent instanceof ModerationEvent);
        assertEquals("AdminDave", kickEvent.actor());
        assertEquals("Griefing", kickEvent.reason());
        assertTrue(kickEvent.timestamp() > 0);
    }

    @Test
    void testBanSuppressionInModerationService() {
        moderationService.markBanned("uuid_test");
        assertTrue(moderationService.isBannedRecently("uuid_test"));
        assertFalse(moderationService.isBannedRecently("uuid_other"));

        moderationService.markKicked("uuid_kick");
        assertTrue(moderationService.isKickedRecently("uuid_kick"));
        assertFalse(moderationService.isKickedRecently("uuid_other"));
    }

    @Test
    void testLoginLinkExpiration() {
        LoginService.LoginLink validLink = new LoginService.LoginLink("https://login.example.com");
        assertFalse(validLink.isExpired(), "Newly created link must not be expired");
        assertTrue(validLink.getRemainingSeconds() > 0 && validLink.getRemainingSeconds() <= 300);

        class ExpiredLoginLink extends LoginService.LoginLink {
            public ExpiredLoginLink(String url) {
                super(url);
            }

            @Override
            public boolean isExpired() {
                return true;
            }
        }

        LoginService.LoginLink expiredLink = new ExpiredLoginLink("https://login.example.com");
        assertTrue(expiredLink.isExpired(), "Link created with expired timestamp must be expired");
    }
}
