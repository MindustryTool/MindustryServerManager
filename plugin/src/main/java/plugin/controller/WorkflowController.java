package plugin.controller;

import java.util.HashMap;

import com.fasterxml.jackson.databind.JsonNode;

import arc.util.Log;
import io.javalin.Javalin;
import plugin.type.WorkflowContext;
import plugin.workflow.Workflow;
import plugin.workflow.errors.WorkflowError;
import plugin.workflow.nodes.WorkflowNode;

public class WorkflowController {
    public static void init(Javalin app) {

        app.get("workflow/nodes", ctx -> {
            ctx.json(Workflow.getNodeTypes());
        });

        app.get("workflow/nodes/{id}/autocomplete", ctx -> {
            String id = ctx.pathParam("id");
            String input = ctx.queryParam("input");
            WorkflowNode node = Workflow.getNodes().get(id);

            if (node == null) {
                ctx.status(404);
                ctx.result();
                return;
            }

            ctx.json(node.autocomplete(input.trim()));
        });

        app.get("workflow/version", ctx -> {
            JsonNode data = Workflow.readWorkflowData();
            if (data == null || data.get("createdAt") == null) {
                ctx.json(0L);
            } else {
                ctx.json(data.get("createdAt").asLong());
            }
        });

        app.get("workflow", ctx -> {
            ctx.json(Workflow.readWorkflowData());
        });

        app.post("workflow", ctx -> {
            JsonNode payload = ctx.bodyAsClass(JsonNode.class);
            Workflow.writeWorkflowData(payload);
        });

        app.post("workflow/load", ctx -> {
            WorkflowContext payload = ctx.bodyAsClass(WorkflowContext.class);
            try {
                Workflow.load(payload);
                ctx.json(Workflow.getWorkflowContext());
            } catch (WorkflowError e) {
                Log.err("Failed to load workflow", e);
                HashMap<String, String> result = new HashMap<>();
                result.put("message", "Failed to load workflow: " + e.getMessage());
                ctx.status(400).json(result);
            }
        });

        app.sse("workflow/events", client -> {
            client.keepAlive();
            client.sendComment("connected");

            client.onClose(() -> {
                Workflow.getWorkflowEventConsumers().remove(client);
            });

            Workflow.getWorkflowEventConsumers().add(client);
        });
    }
}
