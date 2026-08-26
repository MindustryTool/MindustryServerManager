package plugin.graph.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import graph.registry.EventDescriptor;
import graph.registry.FunctionDescriptor;
import graph.registry.GraphRegistry;
import graph.registry.TypeDescriptor;
import plugin.gateway.ApiGateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gateway RPC handlers exposing registry discovery: search (text/kind/
 * category/ownerType + pagination), per-id detail, and full event/type
 * listings. Every search response carries a deterministic fingerprint over
 * the matched ids so clients can detect staleness.
 */
public final class GraphDiscoveryHandlers {

    public static class SearchRequest {
        public String query;
        public String kind;
        public String category;
        public String ownerType;
        public Integer offset;
        public Integer limit;
    }

    public static class DetailRequest {
        public String id;
    }

    private record Entry(String kind, String id, String category,
            String ownerType, String summary) {
    }

    private final GraphRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private plugin.graph.services.GraphDocumentRepository docs;

    public GraphDiscoveryHandlers(GraphRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public GraphDiscoveryHandlers withDocuments(
            plugin.graph.services.GraphDocumentRepository docs) {
        this.docs = docs;
        return this;
    }

    public void registerInto(ApiGateway gateway) {
        gateway.exposeHandler("graph-search", SearchRequest.class, this::search);
        gateway.exposeHandler("graph-detail", DetailRequest.class, this::detail);
        gateway.exposeHandler("graph-events", Void.class, req -> events());
        gateway.exposeHandler("graph-types", Void.class, req -> types());
        if (docs != null) {
            gateway.exposeHandler("graph-doc-get", DetailRequest.class,
                    this::docGet);
            gateway.exposeHandler("graph-doc-save", DocSave.class, this::docSave);
            gateway.exposeHandler("graph-doc-delete", DocSave.class, this::docDelete);
        }
    }

    public Map<String, Object> search(SearchRequest request) {
        SearchRequest q = request == null ? new SearchRequest() : request;
        String text = orEmpty(q.query).toLowerCase();
        String kind = orEmpty(q.kind);
        List<Entry> all = collectEntries(kind);

        List<Entry> filtered = new ArrayList<>();
        for (Entry e : all) {
            if (!kind.isEmpty() && !kind.equals(e.kind())) {
                continue;
            }
            if (!orEmpty(q.category).isEmpty()
                    && !q.category.equals(e.category())) {
                continue;
            }
            if (!orEmpty(q.ownerType).isEmpty()
                    && !q.ownerType.equals(e.ownerType())) {
                continue;
            }
            if (!text.isEmpty() && !(e.id().toLowerCase().contains(text)
                    || e.summary().toLowerCase().contains(text))) {
                continue;
            }
            filtered.add(e);
        }
        int offset = Math.max(0, q.offset == null ? 0 : q.offset);
        int limit = q.limit == null || q.limit <= 0 ? 100 : q.limit;
        List<Entry> page = filtered.subList(
                Math.min(offset, filtered.size()),
                Math.min(offset + limit, filtered.size()));

        List<String> ids = page.stream().map(Entry::id).toList();
        List<Map<String, Object>> resultMaps = new ArrayList<>();
        for (Entry e : page) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", e.kind());
            m.put("id", e.id());
            m.put("category", e.category());
            m.put("ownerType", e.ownerType());
            m.put("summary", e.summary());
            resultMaps.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", filtered.size());
        out.put("results", resultMaps);
        out.put("fingerprint", registry.fingerprint(ids));
        return out;
    }

    public Map<String, Object> detail(DetailRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (request == null || request.id == null) {
            out.put("found", false);
            return out;
        }
        String id = request.id;
        FunctionDescriptor fn = functionOrNull(id);
        if (fn != null) {
            out.put("found", true);
            out.put("kind", "function");
            out.put("descriptor", mapper.valueToTree(fn));
            return out;
        }
        EventDescriptor ev = eventOrNull(id);
        if (ev != null) {
            out.put("found", true);
            out.put("kind", "event");
            out.put("descriptor", mapper.valueToTree(ev));
            return out;
        }
        TypeDescriptor ty = registry.type(id.toLowerCase(java.util.Locale.ROOT));
        if (ty != null) {
            out.put("found", true);
            out.put("kind", "type");
            out.put("descriptor", mapper.valueToTree(ty));
            return out;
        }
        out.put("found", false);
        return out;
    }

    public List<Object> events() {
        return new ArrayList<>(registry.eventsList());
    }

    public List<Object> types() {
        return new ArrayList<>(registry.typesList());
    }

    private List<Entry> collectEntries(String kindFilter) {
        List<Entry> all = new ArrayList<>();
        if (kindFilter.isEmpty() || kindFilter.equals("function")) {
            for (FunctionDescriptor f : registry.functions()) {
                StringBuilder summary = new StringBuilder(
                        orEmpty(f.displayName())).append(' ')
                        .append(orEmpty(f.description()));
                for (String alias : f.aliases()) {
                    summary.append(' ').append(alias);
                }
                all.add(new Entry("function", f.id(), f.category(),
                        f.ownerType(), summary.toString().trim()));
            }
        }
        if (kindFilter.isEmpty() || kindFilter.equals("event")) {
            for (EventDescriptor e : registry.eventsList()) {
                all.add(new Entry("event", e.id(), e.category(), "",
                        e.displayName() + " " + orEmpty(e.description())));
            }
        }
        if (kindFilter.isEmpty() || kindFilter.equals("type")) {
            for (TypeDescriptor t : registry.typesList()) {
                all.add(new Entry("type", t.baseName(), "", "",
                        t.kind() + " " + orEmpty(t.description())));
            }
        }
        return all;
    }

    private FunctionDescriptor functionOrNull(String id) {
        for (FunctionDescriptor f : registry.functions()) {
            if (f.id().equals(id)) {
                return f;
            }
        }
        return null;
    }

    private EventDescriptor eventOrNull(String id) {
        for (EventDescriptor e : registry.eventsList()) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    public static class DocSave {
        public String id;
        public Long expectedRevision;
        public String doc;
    }

    public Map<String, Object> docGet(DetailRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            var stored = request == null || request.id == null
                    ? java.util.Optional.<GraphDocumentRepository.Stored>empty()
                    : docs.find(request.id);
            if (stored.isEmpty()) {
                out.put("found", false);
            } else {
                out.put("found", true);
                out.put("id", stored.get().id());
                out.put("revision", stored.get().revision());
                out.put("doc", stored.get().docJson());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public Map<String, Object> docSave(DocSave request) {
        try {
            long revision = docs.save(request.id, request.expectedRevision,
                    request.doc);
            return Map.of("revision", revision);
        } catch (GraphDocumentRepository.RevisionConflict e) {
            return Map.of("conflict", true,
                    "currentRevision", e.currentRevision);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> docDelete(DocSave request) {
        try {
            boolean removed = docs.delete(request.id, request.expectedRevision);
            return Map.of("deleted", removed);
        } catch (GraphDocumentRepository.RevisionConflict e) {
            return Map.of("conflict", true,
                    "currentRevision", e.currentRevision);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
