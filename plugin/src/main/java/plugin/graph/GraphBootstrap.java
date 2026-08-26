package plugin.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import graph.registry.GraphRegistry;
import graph.registry.RegistryIndexEntry;
import plugin.annotations.Component;
import plugin.annotations.Lazy;
import plugin.gateway.ApiGateway;
import plugin.graph.services.GraphDiscoveryHandlers;
import plugin.graph.services.GraphDocumentRepository;
import plugin.graph.services.GraphSnapshotWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazy bootstrap for the visual graph subsystem: opens the SQLite document
 * store, loads the registry index when present, and registers all graph RPC
 * handlers on first use. Construction is cheap; {@link #ensureStarted(Path,
 * Path)} performs the real work exactly once.
 */
@Component
@Lazy
public class GraphBootstrap {

    private final ApiGateway gateway;
    private final AtomicBoolean started = new AtomicBoolean();

    private GraphRegistry registry;
    private GraphDocumentRepository documents;
    private GraphSnapshotWriter snapshots;

    public GraphBootstrap(ApiGateway gateway) {
        this.gateway = gateway;
    }

    public synchronized boolean ensureStarted(Path docDb, Path snapshotFile) {
        if (!started.compareAndSet(false, true)) {
            return false;
        }
        try {
            Files.createDirectories(docDb.toAbsolutePath().getParent());
        } catch (Exception e) {
            throw new IllegalStateException("cannot create graph data dir", e);
        }
        documents = new GraphDocumentRepository(docDb);
        registry = new GraphRegistry();
        loadIndexQuietly(docDb.getParent() == null
                ? Path.of("registry-index.json")
                : docDb.getParent().resolve("registry-index.json"));
        new GraphDiscoveryHandlers(registry)
                .withDocuments(documents)
                .registerInto(gateway);
        snapshots = GraphSnapshotWriter.forCoreSettings(snapshotFile);
        return true;
    }

    private void loadIndexQuietly(Path indexFile) {
        if (!Files.exists(indexFile)) {
            return;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            CollectionType type = mapper.getTypeFactory()
                    .constructCollectionType(List.class, RegistryIndexEntry.class);
            List<RegistryIndexEntry> entries = mapper.readValue(indexFile.toFile(), type);
            registry.loadIndex(entries, id -> null);
        } catch (Exception e) {
            // a broken index must not prevent the plugin from booting
        }
    }


    public GraphRegistry registryIfStarted() {
        return started.get() ? registry : null;
    }

    public GraphDocumentRepository documentsIfStarted() {
        return started.get() ? documents : null;
    }

    public GraphSnapshotWriter snapshotsIfStarted() {
        return started.get() ? snapshots : null;
    }

    public boolean isStarted() {
        return started.get();
    }
}
