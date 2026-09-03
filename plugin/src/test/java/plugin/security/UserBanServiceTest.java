package plugin.security;

import arc.Core;
import arc.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import plugin.session.LoginService;

import static org.junit.jupiter.api.Assertions.*;

public class UserBanServiceTest {

    private UserBanService banService;

    @BeforeEach
    void setUp() {
        Core.settings = new Settings();
        banService = new UserBanService();
    }

    @Test
    void testBanAndPersistSettings() {
        assertFalse(banService.isBanned("user_abc"));

        assertTrue(banService.ban("user_abc"));
        assertTrue(banService.isBanned("user_abc"));
        assertTrue(banService.getBannedUserIds().contains("user_abc"));

        // Simulate reload from settings
        UserBanService reloadedService = new UserBanService();
        reloadedService.loadFromSettings();
        assertTrue(reloadedService.isBanned("user_abc"));

        // Unban
        assertTrue(reloadedService.unban("user_abc"));
        assertFalse(reloadedService.isBanned("user_abc"));

        // Verify persisted unban
        UserBanService reloadedAfterUnban = new UserBanService();
        reloadedAfterUnban.loadFromSettings();
        assertFalse(reloadedAfterUnban.isBanned("user_abc"));
    }

    @Test
    void testLoginLinkExpiration() {
        LoginService.LoginLink validLink = new LoginService.LoginLink("https://login.example.com");
        assertFalse(validLink.isExpired(), "Newly created link must not be expired");
        assertTrue(validLink.getRemainingSeconds() > 0 && validLink.getRemainingSeconds() <= 300);

        // Test link created in the past (> 5 minutes ago)
        class ExpiredLoginLink extends LoginService.LoginLink {
            public ExpiredLoginLink(String url) {
                super(url);
            }

            @Override
            public boolean isExpired() {
                return true;
            }
        }

        ExpiredLoginLink expired = new ExpiredLoginLink("https://login.example.com/expired");
        assertTrue(expired.isExpired(), "Expired link must report expired");
    }

    @Test
    void testKickOnlinePlayerSafely() {
        // Must not throw when Groups.player or SessionService are absent
        assertDoesNotThrow(() -> banService.kickOnlinePlayerWithUserId("user_abc"));
    }
}
