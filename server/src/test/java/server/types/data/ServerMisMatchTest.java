package server.types.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import common.content.Mod;
import common.server.ServerConfig;
import common.server.ServerMetadata;
import common.server.ServerSnapshot;
import common.server.ServerStatus;

class ServerMisMatchTest {

    private ServerConfig base(UUID id, int port, boolean hub, String hostCommand) {
        return new ServerConfig()
                .setId(id)
                .setName("name")
                .setDescription("desc")
                .setMode("survival")
                .setGamemode("survival")
                .setPort(port)
                .setEnv(Map.of())
                .setImage("image")
                .setHostCommand(hostCommand)
                .setIsHub(hub)
                .setIsAutoTurnOff(true)
                .setIsDefault(false)
                .setIsOfficial(false)
                .setCpu(1f)
                .setMemory(512);
    }

    private ServerMetadata meta(ServerConfig config) {
        return new ServerMetadata().setConfig(config);
    }

    private ServerSnapshot online() {
        return new ServerSnapshot().setStatus(ServerStatus.ONLINE);
    }

    private static boolean has(List<ServerMisMatch> result, MisMatchType type) {
        return result.stream().anyMatch(m -> m.getType() == type);
    }

    @Test
    void jarHashDifferenceReportedAsPluginJar() {
        UUID id = UUID.randomUUID();
        ServerConfig config = base(id, 6567, false, "host");

        List<ServerMisMatch> result = ServerMisMatch.from(
                meta(config), config, online(), List.<Mod>of(), "aaa", "bbb");

        assertTrue(has(result, MisMatchType.PLUGIN_JAR), "jar hash difference must be typed PLUGIN_JAR");
    }

    @Test
    void matchingJarHashNotReported() {
        UUID id = UUID.randomUUID();
        ServerConfig config = base(id, 6567, false, "host");

        List<ServerMisMatch> result = ServerMisMatch.from(
                meta(config), config, online(), List.<Mod>of(), "same", "same");

        assertFalse(has(result, MisMatchType.PLUGIN_JAR), "matching jar hash must not be reported");
    }

    @Test
    void portDriftTypedPort() {
        UUID id = UUID.randomUUID();
        ServerConfig live = base(id, 6567, false, "host");
        ServerConfig wish = base(id, 7000, false, "host");

        List<ServerMisMatch> result = ServerMisMatch.from(meta(live), wish, online(), List.<Mod>of());

        assertTrue(has(result, MisMatchType.PORT), "port change must be typed PORT");
    }

    @Test
    void hostCommandDriftTypedHostCommand() {
        UUID id = UUID.randomUUID();
        ServerConfig live = base(id, 6567, false, "host-a");
        ServerConfig wish = base(id, 6567, false, "host-b");

        List<ServerMisMatch> result = ServerMisMatch.from(meta(live), wish, online(), List.<Mod>of());

        assertTrue(has(result, MisMatchType.HOST_COMMAND), "host command change must be typed HOST_COMMAND");
    }

    @Test
    void nameAndDescriptionDriftTyped() {
        UUID id = UUID.randomUUID();
        ServerConfig live = base(id, 6567, false, "host");
        ServerConfig wish = base(id, 6567, false, "host").setName("other").setDescription("other");

        List<ServerMisMatch> result = ServerMisMatch.from(meta(live), wish, online(), List.<Mod>of());

        assertTrue(has(result, MisMatchType.NAME), "name change must be typed NAME");
        assertTrue(has(result, MisMatchType.DESCRIPTION), "description change must be typed DESCRIPTION");
    }

    @Test
    void autoTurnOffDriftTyped() {
        UUID id = UUID.randomUUID();
        ServerConfig live = base(id, 6567, false, "host").setIsAutoTurnOff(true);
        ServerConfig wish = base(id, 6567, false, "host").setIsAutoTurnOff(false);

        List<ServerMisMatch> result = ServerMisMatch.from(meta(live), wish, online(), List.<Mod>of());

        assertTrue(has(result, MisMatchType.AUTO_TURN_OFF), "auto turn off change must be typed AUTO_TURN_OFF");
    }

    @Test
    void missingModTypedModMissing() {
        UUID id = UUID.randomUUID();
        ServerConfig config = base(id, 6567, false, "host");
        Mod wanted = new Mod().setName("Wanted").setFilename("wanted.jar");

        List<ServerMisMatch> result = ServerMisMatch.from(
                meta(config), config, online(), List.of(wanted));

        assertTrue(has(result, MisMatchType.MOD_MISSING), "wanted-but-not-loaded mod must be MOD_MISSING");
    }

    @Test
    void deletedModTypedModDeleted() {
        UUID id = UUID.randomUUID();
        ServerConfig config = base(id, 6567, false, "host");
        ServerSnapshot state = online().setMods(
                List.of(new Mod().setName("Gone").setFilename("gone.jar")));

        List<ServerMisMatch> result = ServerMisMatch.from(
                meta(config), config, state, List.<Mod>of());

        assertTrue(has(result, MisMatchType.MOD_DELETED), "loaded-but-not-wanted mod must be MOD_DELETED");
    }

    @Test
    void nullEnvFieldsDoNotThrow() {
        UUID id = UUID.randomUUID();
        ServerConfig live = base(id, 6567, false, "host").setEnv(null);
        ServerConfig wish = base(id, 6567, false, "host");

        assertDoesNotThrow(() -> ServerMisMatch.from(meta(live), wish, online(), List.<Mod>of()));
    }
}
