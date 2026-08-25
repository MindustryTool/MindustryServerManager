package graph.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public final class GraphRegistry {

    private final Map<String, FunctionDescriptor> functions = new HashMap<>();
    private final Map<String, PropertyDescriptor> properties = new HashMap<>();
    private final Map<String, EventDescriptor> events = new HashMap<>();
    private final Map<String, TypeDescriptor> types = new HashMap<>();
    private final Map<String, Invoker> invokers = new HashMap<>();

    private final List<RegistryIndexEntry> index = new ArrayList<>();
    private final Map<String, Integer> indexPositions = new HashMap<>();
    private Function<String, FunctionDescriptor> pageSource;
    private final Set<String> loadedPages = new HashSet<>();

    public synchronized void loadIndex(List<RegistryIndexEntry> entries,
                                       Function<String, FunctionDescriptor> lazyPageSource) {
        Objects.requireNonNull(entries, "entries");
        for (RegistryIndexEntry entry : entries) {
            if (indexPositions.containsKey(entry.id())) {
                throw new IllegalArgumentException("Duplicate registry id in index: " + entry.id());
            }
            indexPositions.put(entry.id(), index.size());
            index.add(entry);
        }
        this.pageSource = lazyPageSource;
        this.loadedPages.clear();
    }

    public synchronized void register(FunctionDescriptor descriptor, Invoker invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        putUnique(functions, descriptor.id(), descriptor);
        invokers.put(descriptor.id(), invoker);
        markIndexLoaded(descriptor.id());
    }

    public synchronized void replace(FunctionDescriptor descriptor, Invoker invoker) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(invoker, "invoker");
        if (!functions.containsKey(descriptor.id())) {
            throw new IllegalArgumentException(
                    "Cannot replace unregistered function: " + descriptor.id());
        }
        functions.put(descriptor.id(), descriptor);
        invokers.put(descriptor.id(), invoker);
    }

    public synchronized void register(PropertyDescriptor descriptor) {
        putUnique(properties, descriptor.id(), descriptor);
        markIndexLoaded(descriptor.id());
    }

    public synchronized void register(EventDescriptor descriptor) {
        putUnique(events, descriptor.id(), descriptor);
        markIndexLoaded(descriptor.id());
    }

    public synchronized void register(TypeDescriptor descriptor) {
        putUnique(types, descriptor.baseName().toLowerCase(Locale.ROOT), descriptor);
    }

    private <T> void putUnique(Map<String, T> map, String id, T value) {
        T previous = map.put(id, value);
        if (previous != null) {
            map.put(id, previous);
            throw new IllegalArgumentException("Duplicate registry id: " + id);
        }
    }

    private void markIndexLoaded(String id) {
        Integer position = indexPositions.get(id);
        if (position != null) {
            loadedPages.add(id);
        }
    }

    public synchronized boolean hasFunction(String id) {
        return functions.containsKey(id) || indexPositions.containsKey(id);
    }

    public synchronized FunctionDescriptor function(String id) {
        FunctionDescriptor direct = functions.get(id);
        if (direct != null) {
            return direct;
        }
        materialize(id);
        FunctionDescriptor loaded = functions.get(id);
        if (loaded == null) {
            throw new IllegalArgumentException("Unknown registry id: " + id);
        }
        return loaded;
    }

    public synchronized PropertyDescriptor property(String id) {
        return properties.get(id);
    }

    public synchronized EventDescriptor event(String id) {
        EventDescriptor direct = events.get(id);
        if (direct != null) {
            return direct;
        }
        materialize(id);
        return events.get(id);
    }

    public synchronized Invoker invoker(String functionId) {
        Invoker invoker = invokers.get(functionId);
        if (invoker != null) {
            return invoker;
        }
        materialize(functionId);
        return invokers.get(functionId);
    }

    private void materialize(String id) {
        if (!indexPositions.containsKey(id)) {
            return;
        }
        if (functions.containsKey(id) || properties.containsKey(id) || events.containsKey(id)) {
            return;
        }
        if (loadedPages.contains(id)) {
            return;
        }
        if (pageSource == null) {
            throw new IllegalArgumentException("No page source registered for id: " + id);
        }
        loadedPages.add(id);
        FunctionDescriptor descriptor = pageSource.apply(id);
        if (descriptor != null) {
            if (!id.equals(descriptor.id())) {
                throw new IllegalStateException(
                        "Page source returned mismatched id: expected " + id + " got " + descriptor.id());
            }
            functions.put(id, descriptor);
        }
    }

    public synchronized List<RegistryIndexEntry> search(SearchQuery query) {
        List<RegistryIndexEntry> results = new ArrayList<>();
        String lowered = query.text() == null ? "" : query.text().toLowerCase(Locale.ROOT).trim();
        for (RegistryIndexEntry entry : index) {
            if (!query.kind().isEmpty() && !entry.kind().equals(query.kind())) {
                continue;
            }
            if (!query.category().isEmpty() && !entry.category().equals(query.category())) {
                continue;
            }
            if (!query.ownerType().isEmpty() && !entry.ownerType().equals(query.ownerType())) {
                continue;
            }
            if (lowered.isEmpty() || matches(entry, lowered, query.text().trim())) {
                results.add(entry);
            }
        }
        results.sort((a, b) -> score(b, lowered) - score(a, lowered));
        int from = Math.min(query.offset(), results.size());
        int to = Math.min(from + Math.max(query.limit(), 0), results.size());
        return Collections.unmodifiableList(results.subList(from, to));
    }

    private boolean matches(RegistryIndexEntry entry, String loweredText, String rawText) {
        if (entry.id().toLowerCase(Locale.ROOT).contains(loweredText)) {
            return true;
        }
        if (entry.summary().toLowerCase(Locale.ROOT).contains(loweredText)) {
            return true;
        }
        for (String token : entry.id().split("[.\\-_]")) {
            if (token.toLowerCase(Locale.ROOT).startsWith(loweredText)) {
                return true;
            }
        }
        FunctionDescriptor known = functions.get(entry.id());
        if (known != null) {
            for (String alias : known.aliases()) {
                if (alias.equalsIgnoreCase(rawText)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int score(RegistryIndexEntry entry, String loweredText) {
        if (loweredText.isEmpty()) {
            return 0;
        }
        String idLower = entry.id().toLowerCase(Locale.ROOT);
        if (idLower.equals(loweredText)) {
            return 1000;
        }
        for (String token : entry.id().split("[.\\-_]")) {
            String tokenLower = token.toLowerCase(Locale.ROOT);
            if (tokenLower.equals(loweredText)) {
                return 600 - entry.id().length();
            }
            if (tokenLower.startsWith(loweredText)) {
                return 400 - entry.id().length();
            }
        }
        if (entry.summary().toLowerCase(Locale.ROOT).contains(loweredText)) {
            return 100;
        }
        if (idLower.contains(loweredText)) {
            return 200 - entry.id().length();
        }
        return 0;
    }

    public synchronized String fingerprint(java.util.Collection<String> usedIds) {
        List<String> parts = new ArrayList<>(usedIds);
        Collections.sort(parts);
        StringBuilder sb = new StringBuilder();
        for (String id : parts) {
            FunctionDescriptor fn = functions.get(id);
            if (fn != null) {
                sb.append(id);
                for (Overload overload : fn.overloads()) {
                    sb.append('#').append(overload.hash());
                }
                sb.append('\n');
            } else if (properties.containsKey(id)) {
                PropertyDescriptor property = properties.get(id);
                sb.append(id).append("#prop#").append(property.valueType().print())
                        .append('#').append(property.writable()).append('\n');
            } else if (events.containsKey(id)) {
                EventDescriptor event = events.get(id);
                sb.append(id).append("#event#");
                for (ParamDescriptor param : event.payload()) {
                    sb.append(param.type().print()).append(',');
                }
                sb.append('\n');
            } else if (loadedPages.contains(id)) {
                throw new IllegalStateException("Registered page '" + id
                        + "' did not produce a descriptor");
            }
        }
        return shortHash(sb.toString());
    }

    private static String shortHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record SearchQuery(String text, String kind, String category, String ownerType,
                              int offset, int limit) {

        public static SearchQuery all() {
            return new SearchQuery("", "", "", "", 0, Integer.MAX_VALUE);
        }

        public static SearchQuery text(String text) {
            return new SearchQuery(text, "", "", "", 0, Integer.MAX_VALUE);
        }
    }
}
