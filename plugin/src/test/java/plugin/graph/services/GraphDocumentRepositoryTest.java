package plugin.graph.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDocumentRepositoryTest {

    @TempDir
    Path tempDir;

    private GraphDocumentRepository repo;

    private static final String DOC_V1 =
            "{\"version\":1,\"id\":\"welcome\",\"nodes\":[],\"edges\":[]}";
    private static final String DOC_V2 =
            "{\"version\":1,\"id\":\"welcome\",\"nodes\":[{\"id\":\"n1\"}],\"edges\":[]}";

    @BeforeEach
    void setUp() {
        repo = new GraphDocumentRepository(tempDir.resolve("docs.sqlite"));
    }

    @Test
    void createReadUpdateRoundtripBumpsRevisions() throws Exception {
        assertTrue(repo.find("welcome").isEmpty());

        long r1 = repo.save("welcome", null, DOC_V1);
        assertEquals(1, r1);
        assertEquals(1, repo.find("welcome").orElseThrow().revision());
        assertEquals(DOC_V1, repo.find("welcome").orElseThrow().docJson());

        long r2 = repo.save("welcome", 1L, DOC_V2);
        assertEquals(2, r2);
        assertEquals(DOC_V2, repo.find("welcome").orElseThrow().docJson());

        List<GraphDocumentRepository.Stored> all = repo.list();
        assertEquals(1, all.size());
        assertEquals("welcome", all.get(0).id());
    }

    @Test
    void staleRevisionRejectedWithCurrentReported() throws Exception {
        repo.save("welcome", null, DOC_V1);
        long current = repo.save("welcome", 1L, DOC_V2);

        GraphDocumentRepository.RevisionConflict conflict = assertThrows(
                GraphDocumentRepository.RevisionConflict.class,
                () -> repo.save("welcome", 1L, DOC_V1),
                "updating against the previous revision must conflict");
        assertEquals(current, conflict.currentRevision);

        assertThrows(GraphDocumentRepository.RevisionConflict.class,
                () -> repo.save("welcome", 99L, DOC_V1));

        // create-only against an existing id also conflicts
        assertThrows(GraphDocumentRepository.RevisionConflict.class,
                () -> repo.save("welcome", null, DOC_V1));
    }

    @Test
    void deleteHonoursExpectedRevisionAndReportsMissing() throws Exception {
        repo.save("welcome", null, DOC_V1);
        long rev = repo.save("welcome", 1L, DOC_V2);

        assertThrows(GraphDocumentRepository.RevisionConflict.class,
                () -> repo.delete("welcome", 1L));
        assertFalse(repo.delete("missing", null));
        assertFalse(repo.delete("missing", 5L));

        assertTrue(repo.delete("welcome", rev));
        assertTrue(repo.find("welcome").isEmpty());

        // recreate after delete starts at revision 1 again
        assertEquals(1, repo.save("welcome", null, DOC_V1));
    }

    @Test
    void multipleDocumentsListedInIdOrder() throws Exception {
        repo.save("zeta", null, DOC_V1);
        repo.save("alpha", null, DOC_V1);
        repo.save("mid", null, DOC_V1);

        List<GraphDocumentRepository.Stored> all = repo.list();
        assertEquals(List.of("alpha", "mid", "zeta"),
                all.stream().map(GraphDocumentRepository.Stored::id).toList());
    }
}
