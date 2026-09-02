package plugin.graph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphBootstrapTest {

    @Test
    void ensureStartedRegistersHandlersAndPersistsDocumentsOnce(
            @TempDir Path tempDir) throws Exception {

        plugin.gateway.ApiGateway gateway = new plugin.gateway.ApiGateway(null, null);
        GraphBootstrap bootstrap = new GraphBootstrap(gateway);
        assertFalse(bootstrap.isStarted());

        Path db = tempDir.resolve("docs").resolve("graph.sqlite");
        assertTrue(bootstrap.ensureStarted(db, tempDir.resolve("snap.jsonl")));
        assertFalse(bootstrap.ensureStarted(db, tempDir.resolve("snap.jsonl")),
                "second call must be a no-op");

        assertTrue(bootstrap.isStarted());
        assertTrue(bootstrap.registryIfStarted() != null);
        assertTrue(bootstrap.documentsIfStarted() != null);
        assertTrue(bootstrap.snapshotsIfStarted() != null);

        // document handlers are live: save + read back through the repository
        long rev = bootstrap.documentsIfStarted()
                .save("welcome", null, "{\"id\":\"welcome\"}");
        assertEquals(1, rev);
        assertEquals("{\"id\":\"welcome\"}",
                bootstrap.documentsIfStarted().find("welcome").orElseThrow().docJson());

        assertTrue(Files.exists(db));
    }
}
