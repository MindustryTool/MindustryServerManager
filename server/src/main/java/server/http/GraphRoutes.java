package server.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import server.service.GatewayService;
import server.utils.ApiError;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST surface for the visual graph system. Discovery and document CRUD are
 * proxied over the gateway RPC to the hosting plugin node, which owns the
 * registry and the SQLite document store.
 *
 * Conditional requests: GET document responses carry ETag "<revision>";
 * PUT/DELETE accept If-Match forwarded as the optimistic expectation; a
 * stale expectation yields 409 with the current revision.
 */
public final class GraphRoutes {

    private GraphRoutes() {
    }

    public static void register(Javalin app, GatewayService gateway) {
        app.get("/api/v2/graph/functions", ctx -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", ctx.queryParam("query"));
            payload.put("kind", "function");
            payload.put("category", ctx.queryParam("category"));
            payload.put("ownerType", ctx.queryParam("ownerType"));
            payload.put("offset", parseInt(ctx.queryParam("offset")));
            payload.put("limit", parseInt(ctx.queryParam("limit")));
            ctx.json(proxy(ctx, gateway, "graph-search", payload));
        });

        app.get("/api/v2/graph/events", ctx ->
                ctx.json(proxy(ctx, gateway, "graph-events", null)));

        app.get("/api/v2/graph/types", ctx ->
                ctx.json(proxy(ctx, gateway, "graph-types", null)));

        app.get("/api/v2/graphs/{id}", ctx -> {
            JsonNode node = proxy(ctx, gateway, "graph-doc-get",
                    Map.of("id", ctx.pathParam("id")));
            if (node.has("found") && node.get("found").asBoolean()) {
                long revision = node.get("revision").asLong();
                ctx.header("ETag", "\"" + revision + "\"");
                ctx.header("Cache-Control", "no-cache");
            }
            ctx.json(node);
        });

        app.post("/api/v2/graphs", ctx -> {
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", body.path("id").asText());
            payload.put("expectedRevision", null);
            payload.put("doc", body.toString());
            respond(ctx, proxy(ctx, gateway, "graph-doc-save", payload), 201);
        });

        app.put("/api/v2/graphs/{id}", ctx -> {
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", ctx.pathParam("id"));
            payload.put("expectedRevision", parseIfMatch(ctx.header("If-Match")));
            payload.put("doc", body.toString());
            respond(ctx, proxy(ctx, gateway, "graph-doc-save", payload), 200);
        });

        app.delete("/api/v2/graphs/{id}", ctx -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", ctx.pathParam("id"));
            payload.put("expectedRevision", parseIfMatch(ctx.header("If-Match")));
            respond(ctx, proxy(ctx, gateway, "graph-doc-delete", payload), 200);
        });
    }

    /** Transport-agnostic plugin RPC used by tests and the gateway adapter. */
    public interface GraphRpc {
        boolean available();

        JsonNode call(String type, Object payload) throws Exception;
    }

    public static void register(Javalin app, GraphRpc rpc) {
        app.get("/api/v2/graph/functions", ctx -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", ctx.queryParam("query"));
            payload.put("kind", "function");
            payload.put("category", ctx.queryParam("category"));
            payload.put("ownerType", ctx.queryParam("ownerType"));
            payload.put("offset", parseInt(ctx.queryParam("offset")));
            payload.put("limit", parseInt(ctx.queryParam("limit")));
            ctx.json(call(rpc, "graph-search", payload));
        });

        app.get("/api/v2/graph/events", ctx ->
                ctx.json(call(rpc, "graph-events", null)));

        app.get("/api/v2/graph/types", ctx ->
                ctx.json(call(rpc, "graph-types", null)));

        app.get("/api/v2/graphs/{id}", ctx -> {
            JsonNode node = call(rpc, "graph-doc-get",
                    Map.of("id", ctx.pathParam("id")));
            if (node.has("found") && node.get("found").asBoolean()) {
                long revision = node.get("revision").asLong();
                ctx.header("ETag", "\"" + revision + "\"");
                ctx.header("Cache-Control", "no-cache");
            }
            ctx.json(node);
        });

        app.post("/api/v2/graphs", ctx -> {
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", body.path("id").asText());
            payload.put("expectedRevision", null);
            payload.put("doc", body.toString());
            respond(ctx, call(rpc, "graph-doc-save", payload), 201);
        });

        app.put("/api/v2/graphs/{id}", ctx -> {
            JsonNode body = ctx.bodyAsClass(JsonNode.class);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", ctx.pathParam("id"));
            payload.put("expectedRevision", parseIfMatch(ctx.header("If-Match")));
            payload.put("doc", body.toString());
            respond(ctx, call(rpc, "graph-doc-save", payload), 200);
        });

        app.delete("/api/v2/graphs/{id}", ctx -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", ctx.pathParam("id"));
            payload.put("expectedRevision", parseIfMatch(ctx.header("If-Match")));
            respond(ctx, call(rpc, "graph-doc-delete", payload), 200);
        });
    }

    private static JsonNode call(GraphRpc rpc, String type, Object payload) {
        if (!rpc.available()) {
            throw new ApiError(503, "No plugin node connected");
        }
        try {
            return rpc.call(type, payload);
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ApiError(502, "Plugin RPC failed: " + cause.getMessage(), e);
        }
    }

    private static JsonNode proxy(io.javalin.http.Context ctx,
            GatewayService gateway, String type, Object payload) {
        GatewayService.GatewayClient client = gateway.anyNode();
        if (client == null) {
            throw new ApiError(503, "No plugin node connected");
        }
        try {
            return client.graphRequest(type, payload)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (ApiError e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ApiError(502, "Plugin RPC failed: " + cause.getMessage(), e);
        }
    }

    private static void respond(io.javalin.http.Context ctx, JsonNode result,
            int successStatus) {
        if (result.has("conflict") && result.get("conflict").asBoolean()) {
            ctx.status(409).json(result);
            return;
        }
        ctx.status(successStatus).json(result);
    }

    private static Long parseIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiError(400, "Invalid If-Match revision: " + header);
        }
    }

    private static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "Invalid integer parameter: " + raw);
        }
    }
}
