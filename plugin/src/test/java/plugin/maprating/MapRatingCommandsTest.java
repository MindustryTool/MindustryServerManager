package plugin.maprating;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import plugin.menus.preview.MockSession;
import plugin.session.Session;
import plugin.vote.RtvMenu;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MapRatingCommandsTest {

    @Test
    void testMapsCommandOpensRtvMenu() {
        AtomicBoolean sent = new AtomicBoolean(false);
        RtvMenu stubMenu = new RtvMenu(null, null) {
            @Override
            public void send(Session session, Integer state) {
                sent.set(true);
            }
        };

        MapRatingCommands commands = new MapRatingCommands(null, stubMenu);
        Session session = MockSession.builder().build();

        commands.maps(session, null);
        assertTrue(sent.get(), "maps command should send RtvMenu");
    }
}
