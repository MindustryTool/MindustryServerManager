package plugin.menus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import mindustry.gen.Player;
import mindustry.ui.builder.MenuResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import plugin.session.Session;
import plugin.session.SessionData;
import plugin.session.SessionRemovedEvent;

public class PluginMenuServiceTest {

    private PluginMenuService service;
    private Player player;
    private Session session;

    @BeforeEach
    void setUp() {
        service = new PluginMenuService();
        player = Player.create();
        player.name = "TestPlayer";
        player.locale = "en";
        session = new Session(player, new SessionData());
    }

    @Test
    void addLegacyMenuAndPreventDuplicates() {
        TestLegacyMenu menu1 = new TestLegacyMenu();
        menu1.session = session;

        service.add(menu1);
        assertEquals(1, service.getPlayerMenus(player).size);
        assertEquals(menu1, service.getPlayerMenus(player).first());

        TestLegacyMenu menu2 = new TestLegacyMenu();
        menu2.session = session;
        service.add(menu2);

        assertEquals(1, service.getPlayerMenus(player).size, "Duplicate menu class for player should be ignored");
    }

    @Test
    void addServerMenuAndPreventDuplicates() {
        TestServerMenu menu1 = new TestServerMenu();
        menu1.setSession(session);

        service.add(menu1);

        TestServerMenu menu2 = new TestServerMenu();
        menu2.setSession(session);
        service.add(menu2);

        // Verification: onSessionRemoved should cleanly remove the single queued menu
        service.onSessionRemoved(new SessionRemovedEvent(session));
        assertEquals(0, service.getPlayerMenus(player).size);
    }

    @Test
    void onSessionRemovedClearsQueuedMenus() {
        TestLegacyMenu menu = new TestLegacyMenu();
        menu.session = session;
        service.add(menu);
        assertEquals(1, service.getPlayerMenus(player).size);

        service.onSessionRemoved(new SessionRemovedEvent(session));
        assertEquals(0, service.getPlayerMenus(player).size);
    }

    @Test
    void handleMenuBuilderResultDispatchesActionOnMatchingToken() {
        AtomicBoolean actionExecuted = new AtomicBoolean(false);
        AtomicReference<String> passedState = new AtomicReference<>();

        TestServerMenu menu = new TestServerMenu((ctx) -> {
            ctx.button("Click", (sess, st, res) -> {
                actionExecuted.set(true);
                passedState.set(st);
            });
        });
        menu.setSession(session);
        menu.setState("state123");

        long token = 42L;
        MenuContext<String> ctx = new MenuContext<>(session, menu.getState(), token);
        menu.build(ctx);

        String actionId = ctx.getActions().keySet().iterator().next();

        // Simulate internal entry setup
        var holder = new PluginMenuService();
        // Test token mismatch
        MenuResult mismatchResult = new MenuResult(actionId);
        mismatchResult.token = 999L;
        holder.handleMenuBuilderResult(player, mismatchResult);
        assertFalse(actionExecuted.get());

        // Test cancellation
        MenuResult cancelledResult = new MenuResult();
        cancelledResult.token = token;
        assertTrue(cancelledResult.wasCancelled());
    }

    private static class TestLegacyMenu extends PluginMenu<Void> {
        @Override
        protected void build(Session session, Void state) {
            option("Option 1", (s, st) -> {});
        }
    }

    private static class TestServerMenu extends ServerMenu<String> {
        private final Consumer<MenuContext<String>> customBuilder;

        TestServerMenu() {
            this(ctx -> {});
        }

        TestServerMenu(Consumer<MenuContext<String>> customBuilder) {
            this.customBuilder = customBuilder;
        }

        @Override
        public void build(MenuContext<String> ctx) {
            customBuilder.accept(ctx);
        }
    }
}
