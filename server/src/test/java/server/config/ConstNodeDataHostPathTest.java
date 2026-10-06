package server.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import server.manager.DockerNodeManager;

class ConstNodeDataHostPathTest {

    @Test
    void explicitValueWins() {
        assertEquals("/srv/data", Const.resolveNodeDataHostPath("/app/data", "/srv/data"));
        assertEquals("E:/work/data", Const.resolveNodeDataHostPath("/app/data", "E:/work/data"));
    }

    @Test
    void blankFallsBackToContainerPath() {
        assertEquals("/app/data", Const.resolveNodeDataHostPath("/app/data", null));
        assertEquals("/app/data", Const.resolveNodeDataHostPath("/app/data", "  "));
        assertEquals("./data", Const.resolveNodeDataHostPath("./data", ""));
    }

    @Test
    void windowsSeparatorsNormalized() {
        assertEquals("E:/work/data", Const.resolveNodeDataHostPath("/app/data", "E:\\work\\data"));
        assertEquals("E:/work/data", Const.resolveNodeDataHostPath("/app/data", "E:/work/data/"));
        assertEquals("E:/work/data", Const.resolveNodeDataHostPath("/app/data", "  E:\\work\\data\\  "));
    }

    @Test
    void defaultResolverNeverBlank() {
        String resolved = Const.resolveNodeDataHostPath();

        assertNotNull(resolved);
        assertFalse(resolved.isBlank());
    }

    @Test
    void bindSourceFollowsHostBase() {
        var localPath = Paths.get("/app/data/servers/sid/config");

        assertEquals(localPath.toString(),
                DockerNodeManager.bindSourceFor("sid", localPath));
    }
}
