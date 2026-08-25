package graph.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GraphNode node(String id, String type, String jsonFields) throws Exception {
        var fields = MAPPER.readTree(jsonFields);
        var data = new java.util.LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        fields.fields().forEachRemaining(e -> data.put(e.getKey(), e.getValue()));
        return GraphNode.of(id, type, data);
    }

    @Nested
    class PortAddresses {
        @Test
        void parseAndPrint() {
            PortAddress address = PortAddress.parse("join.player");
            assertEquals("join", address.nodeId());
            assertEquals("player", address.port());
            assertEquals("join.player", address.print());
        }

        @Test
        void rejectsMalformedAddresses() {
            assertThrows(IllegalArgumentException.class, () -> PortAddress.parse(""));
            assertThrows(IllegalArgumentException.class, () -> PortAddress.parse(".port"));
            assertThrows(IllegalArgumentException.class, () -> PortAddress.parse("node."));
            assertThrows(IllegalArgumentException.class, () -> PortAddress.parse("node"));
            assertThrows(IllegalArgumentException.class, () -> PortAddress.parse("a.b.c"));
            assertThrows(IllegalArgumentException.class, () -> PortAddress.parse("a b.c"));
        }

        @Test
        void identifierRules() {
            assertTrue(PortAddress.isIdentifier("_a1"));
            assertTrue(PortAddress.isIdentifier("out0"));
            assertFalse(PortAddress.isIdentifier(""));
            assertFalse(PortAddress.isIdentifier("1abc"));
            assertFalse(PortAddress.isIdentifier("has space"));
            assertFalse(PortAddress.isIdentifier(null));
        }

        @Test
        void edgeOfParsesBothEnds() {
            GraphEdge edge = GraphEdge.of("n1.out", "n2.in");
            assertEquals(new PortAddress("n1", "out"), edge.from());
            assertEquals(new PortAddress("n2", "in"), edge.to());
        }
    }

    @Nested
    class NodeModel {
        @Test
        void idAndTypeStrippedFromData() throws Exception {
            GraphNode n = node("m1", "call",
                    "{\"id\":\"WRONG\",\"type\":\"ALSO_WRONG\",\"function\":\"mindustry.player.sendMessage\"}");
            assertEquals("m1", n.id());
            assertEquals("call", n.type());
            assertEquals(1, n.data().size());
            assertEquals("mindustry.player.sendMessage", n.getString("function"));
        }

        @Test
        void typedAccessorsValidatePresence() throws Exception {
            GraphNode n = node("x", "log", "{\"level\":2}");
            assertThrows(IllegalArgumentException.class, () -> n.getString("missing"));
            assertThrows(IllegalArgumentException.class, () -> n.getString("level"));
            assertEquals(2, n.getInt("level"));
            assertTrue(n.has("level"));
            assertFalse(n.has("nope"));
        }

        @Test
        void equalityIsStructural() throws Exception {
            GraphNode a = node("n", "if", "{\"a\":true}");
            GraphNode b = node("n", "if", "{\"a\":true}");
            GraphNode c = node("n", "if", "{\"a\":false}");
            assertEquals(a, b);
            assertNotEquals(a, c);
        }
    }

    @Nested
    class CanonicalForm {
        @Test
        void canonicalIgnoresEditorAndRevisionOrdering() throws Exception {
            String rawA = """
                    {"version":1,"revision":3,"id":"welcome-flow",
                     "editor":{"nodes":{"join":{"x":10}},"zoom":2.5},
                     "nodes":[{"id":"join","type":"event","event":"mindustry.player.join"},
                              {"id":"msg","type":"call","function":"mindustry.player.sendMessage"}],
                     "edges":[]}
                    """;
            String rawB = """
                    {"version":1,"revision":99,"id":"welcome-flow",
                     "nodes":[{"id":"msg","type":"call","function":"mindustry.player.sendMessage"},
                              {"id":"join","type":"event","event":"mindustry.player.join"}],
                     "edges":[],"editor":{"zoom":9.9}}
                    """;
            GraphDocument docA = GraphParser.parse(rawA).document();
            GraphDocument docB = GraphParser.parse(rawB).document();
            assertEquals(CanonicalSerializer.serialize(docA), CanonicalSerializer.serialize(docB));
            assertNotEquals(docA.revision(), docB.revision());
        }

        @Test
        void canonicalSortsNodesEdgesVariablesAndKeys() throws Exception {
            GraphDocument doc = GraphDocument.initial(
                    "t",
                    List.of(new VariableDecl("zebra", "SERVER", "Int"),
                            new VariableDecl("alpha", "LOCAL", "String")),
                    List.of(node("b", "call", "{\"zzz\":1,\"aaa\":2}"),
                            node("a", "event", "{\"event\":\"e\"}")),
                    List.of(GraphEdge.of("b.out", "a.in")),
                    null);
            String canonical = CanonicalSerializer.serialize(doc);
            int alphaIdx = canonical.indexOf("\"alpha\"");
            int zebraIdx = canonical.indexOf("\"zebra\"");
            int nodeA = canonical.indexOf("\"id\":\"a\"");
            int nodeB = canonical.indexOf("\"id\":\"b\"");
            assertTrue(alphaIdx >= 0 && zebraIdx > alphaIdx);
            assertTrue(nodeA >= 0 && nodeB > nodeA);
            int aaa = canonical.indexOf("\"aaa\"");
            int zzz = canonical.indexOf("\"zzz\"");
            assertTrue(aaa >= 0 && zzz > aaa);
            assertTrue(canonical.indexOf("edges") > 0);
            assertFalse(canonical.contains("\"editor\""));
            assertFalse(canonical.contains("\"revision\""));
        }

        @Test
        void roundTripThroughCanonicalIsStable() throws Exception {
            String raw = """
                    {"version":1,"id":"flow","revision":7,
                     "variables":[{"name":"count","scope":"SERVER","type":"Int"}],
                     "nodes":[{"id":"ev","type":"event","event":"mindustry.player.join"},
                              {"id":"delay5","type":"delay","seconds":5},
                              {"id":"say","type":"call","function":"mindustry.player.sendMessage"}],
                     "edges":[{"from":"ev.player","to":"say.player"},{"from":"ev.flow","to":"delay5.exec"}],
                     "editor":{"nodes":{"ev":{"x":1}}}}
                    """;
            GraphDocument first = GraphParser.parse(raw).document();
            String canonical1 = CanonicalSerializer.serialize(first);
            GraphDocument second = GraphParser.parse(canonical1).document();
            String canonical2 = CanonicalSerializer.serialize(second);
            assertEquals(canonical1, canonical2);
            assertEquals(second.version(), GraphDocument.SUPPORTED_SCHEMA_VERSION);
            assertEquals(2, second.edges().size());
            assertEquals(1, second.variables().size());
        }
    }
}
