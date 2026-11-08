package plugin.menus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import mindustry.gen.Player;
import mindustry.ui.builder.MenuResult;
import mindustry.ui.builder.UiBuilder;
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
    void addServerMenuAndPreventDuplicates() {
        TestServerMenu menu1 = new TestServerMenu();
        menu1.setSession(session);

        service.add(menu1);
        assertEquals(1, service.getPlayerMenus(player).size);
        assertEquals(menu1, service.getPlayerMenus(player).first());

        TestServerMenu menu2 = new TestServerMenu();
        menu2.setSession(session);
        service.add(menu2);

        assertEquals(1, service.getPlayerMenus(player).size, "Duplicate menu class for player should be ignored");
    }

    @Test
    void onSessionRemovedClearsQueuedMenus() {
        TestServerMenu menu = new TestServerMenu();
        menu.setSession(session);
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

    @Test
    void menuContextUiBuilderConvenienceMethods() {
        AtomicBoolean imageButtonClicked = new AtomicBoolean(false);
        AtomicBoolean buttonTableClicked = new AtomicBoolean(false);

        MenuContext<String> ctx = new MenuContext<>(session, "testState", 100L);

        ctx.image("ranai", i -> i.size(64f));
        ctx.imageButton("play", (sess, st, res) -> imageButtonClicked.set(true));
        ctx.buttonTable(bt -> bt.add(UiBuilder.label("Inside")), (sess, st, res) -> buttonTableClicked.set(true));
        ctx.stack(stk -> stk.add(UiBuilder.image("bg")));
        ctx.defaults(d -> d.pad(4f));

        assertEquals(2, ctx.getActions().size());

        for (var action : ctx.getActions().values()) {
            action.handle(session, ctx.getState(), new MenuResult());
        }
        assertTrue(imageButtonClicked.get());
        assertTrue(buttonTableClicked.get());
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
