package plugin.menus.preview;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import plugin.session.Session;
import plugin.utils.Tr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockSessionTest {

    @Test
    void mockSessionBuildsValidPlayerAndSessionState() {
        Session session = MockSession.builder()
                .locale("vi")
                .name("TestAdmin")
                .admin(true)
                .level(42)
                .build();

        assertNotNull(session.player);
        assertNotNull(session.player.con);
        assertEquals("TestAdmin", session.player.name);
        assertEquals(Locale.forLanguageTag("vi"), session.locale);
        assertTrue(session.isAdmin());
        assertEquals(42, session.currentLevel);
    }

    @Test
    void localizationResolvesCorrectlyForMockSession() {
        Session sessionEn = MockSession.builder().locale("en").build();
        Session sessionVi = MockSession.builder().locale("vi").build();

        String enVal = Tr.t(sessionEn, "rtv.btn_close");
        String viVal = Tr.t(sessionVi, "rtv.btn_close");

        assertNotNull(enVal);
        assertNotNull(viVal);
        assertFalse(enVal.isEmpty());
        assertFalse(viVal.isEmpty());
    }
}
