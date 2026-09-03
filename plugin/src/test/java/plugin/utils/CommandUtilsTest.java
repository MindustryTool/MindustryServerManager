package plugin.utils;

import java.lang.reflect.Method;

import arc.Core;
import arc.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import plugin.annotations.Param;
import plugin.core.Registry;
import plugin.security.UserBanService;
import plugin.session.Session;

import static org.junit.jupiter.api.Assertions.*;

public class CommandUtilsTest {

    static class DummyCommands {
        public void testClientCommand(Session session, @Param(name = "id") String id, UserBanService banService) {
        }

        public void testServerCommand(@Param(name = "id") String id, UserBanService banService) {
        }
    }

    @BeforeEach
    void setUp() {
        Core.settings = new Settings();
        Registry.destroy();
        Registry.init(UserBanService.class);
    }

    @Test
    void testAutoInjectDependencyInServerCommand() throws Exception {
        Method method = DummyCommands.class.getMethod("testServerCommand", String.class, UserBanService.class);
        String[] args = new String[]{"user_123"};

        Object[] resolved = CommandUtils.mapParams(method, args, null);
        assertNotNull(resolved);
        assertEquals(2, resolved.length);
        assertEquals("user_123", resolved[0]);
        assertInstanceOf(UserBanService.class, resolved[1]);
    }

    @Test
    void testParamTextExcludesInjectedDependencies() throws Exception {
        Method method = DummyCommands.class.getMethod("testServerCommand", String.class, UserBanService.class);
        String text = CommandUtils.toParamText(method).trim();
        assertEquals("<id>", text, "toParamText should only include @Param annotations");
    }
}
