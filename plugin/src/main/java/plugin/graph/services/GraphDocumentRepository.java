package plugin.graph.services;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite persistence for graph documents: JSON text rows keyed by id with a
 * monotonic revision counter and optimistic conflict detection.
 */
public final class GraphDocumentRepository implements AutoCloseable {

    public static final class RevisionConflict extends RuntimeException {
        public final long currentRevision;

        RevisionConflict(long currentRevision) {
            super("revision conflict; current=" + currentRevision);
            this.currentRevision = currentRevision;
        }
    }

    public record Stored(String id, long revision, String docJson) {
    }

    private final Path dbFile;

    public GraphDocumentRepository(Path dbFile) {
        this.dbFile = dbFile;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS graph_docs ("
                    + "id TEXT PRIMARY KEY,"
                    + "revision INTEGER NOT NULL,"
                    + "doc TEXT NOT NULL)");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot init graph doc store", e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    /**
     * Creates or updates the document. {@code expectedRevision == null} means
     * create-only; any mismatch with the stored revision raises
     * {@link RevisionConflict} carrying the current revision.
     *
     * @return the new revision
     */
    public synchronized long save(String id, Long expectedRevision, String docJson)
            throws SQLException {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                Long current = readRevision(c, id);
                if (expectedRevision == null && current != null) {
                    throw new RevisionConflict(current);
                }
                if (expectedRevision != null
                        && (current == null || current != expectedRevision)) {
                    throw new RevisionConflict(current == null ? 0L : current);
                }
                long next = (current == null ? 0L : current) + 1;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO graph_docs (id, revision, doc) VALUES (?, ?, ?)"
                                + " ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,"
                                + " doc=excluded.doc")) {
                    ps.setString(1, id);
                    ps.setLong(2, next);
                    ps.setString(3, docJson);
                    ps.executeUpdate();
                }
                c.commit();
                return next;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public synchronized Optional<Stored> find(String id) throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, revision, doc FROM graph_docs WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Stored(rs.getString(1), rs.getLong(2),
                        rs.getString(3)));
            }
        }
    }

    public synchronized List<Stored> list() throws SQLException {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, revision, doc FROM graph_docs ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            List<Stored> out = new ArrayList<>();
            while (rs.next()) {
                out.add(new Stored(rs.getString(1), rs.getLong(2), rs.getString(3)));
            }
            return out;
        }
    }

    public synchronized boolean delete(String id, Long expectedRevision)
            throws SQLException {
        try (Connection c = connect()) {
            Long current = readRevision(c, id);
            if (current == null) {
                return false;
            }
            if (expectedRevision != null && expectedRevision != current) {
                throw new RevisionConflict(current);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM graph_docs WHERE id = ?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        }
    }

    private Long readRevision(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT revision FROM graph_docs WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    @Override
    public void close() {
        // connections are opened per operation; nothing pooled to release
    }
}
