package plugin.vote;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import plugin.utils.TrCatalog;

import static org.junit.jupiter.api.Assertions.*;

public class VoteKickServiceTest {

    @Test
    void votekickTranslationKeysExistAndInterpolate() throws Exception {
        String enJson = new String(getClass().getResourceAsStream("/i18n/en.json").readAllBytes(),
                StandardCharsets.UTF_8);
        TrCatalog catalog = new TrCatalog();
        catalog.load("en", enJson, null);

        String disabled = catalog.resolve(Locale.ENGLISH, "votekick.disabled");
        assertTrue(disabled.contains("Vote-kick is disabled"), "Disabled message should match");

        String template = catalog.resolve(Locale.ENGLISH, "votekick.vote_started");
        String started = catalog.interpolate(template,
                "initiator", "Player1", "target", "Player2", "votes", 1, "required", 2);
        assertTrue(started.contains("Player1"), "Should interpolate initiator");
        assertTrue(started.contains("Player2"), "Should interpolate target");
        assertTrue(started.contains("1/2"), "Should interpolate votes and required");

        String viJson = new String(getClass().getResourceAsStream("/i18n/vi.json").readAllBytes(),
                StandardCharsets.UTF_8);
        catalog.load("vi", viJson, null);

        String viTemplate = catalog.resolve(Locale.forLanguageTag("vi"), "votekick.vote_started");
        String viStarted = catalog.interpolate(viTemplate,
                "initiator", "Player1", "target", "Player2", "votes", 1, "required", 2);
        assertTrue(viStarted.contains("Player1"), "Should interpolate initiator in Vietnamese");
        assertTrue(viStarted.contains("Player2"), "Should interpolate target in Vietnamese");
    }

    @Test
    void defaultVoteKickServiceState() {
        VoteKickService service = new VoteKickService(null, null);
        assertFalse(service.isVoting());
        assertNull(service.getCurrentSession());
        assertEquals(0, service.getRemainingSeconds());
        assertEquals(0, service.getVotes());
        assertEquals(2, service.getVotesRequired());
    }

    @Test
    void sessionTracksVotesCorrectly() {
        VoteKickService.VoteKickSession session = new VoteKickService.VoteKickSession(null, null, "Griefing");
        assertEquals("Griefing", session.reason);
        assertNotNull(session.startTime);

        session.voted.put("uuid1", 1);
        session.voted.put("uuid2", -1);
        assertEquals(1, session.voted.get("uuid1"));
        assertEquals(-1, session.voted.get("uuid2"));
    }
}
