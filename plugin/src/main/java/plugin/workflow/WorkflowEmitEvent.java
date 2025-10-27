package plugin.workflow;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import arc.util.Log;
import lombok.Getter;
import lombok.ToString;
import mindustry.gen.Groups;
import plugin.workflow.nodes.WorkflowNode;

@ToString(exclude = { "context" })
public class WorkflowEmitEvent {
    private static final int MAX_STEP = 5000;

    private final int step;

    @Getter
    private final WorkflowNode current;

    @Getter
    private final Map<String, Object> variables;

    @Getter
    private final Workflow context;

    public WorkflowEmitEvent putValue(String name, Object value) {
        variables.put(name, value);
        Log.debug("Add variable: " + name + " = " + value);

        HashMap<String, Object> vars = new HashMap<>();

        vars.put(name, value);

        Workflow.sendWorkflowEvent(new WorkflowEvent<>(current.getId(), "SET", vars));

        return this;
    }

    private WorkflowEmitEvent(int step, WorkflowNode currentNode, Workflow context, Map<String, Object> variables) {
        this.step = step;
        this.current = currentNode;
        this.context = context;
        this.variables = variables;

        putDefaultValues(variables);

        Log.debug("step: " + step + " current: " + current.getName() + ":" + currentNode.getId() + " variables: "
                + variables);
    }

    public static void putDefaultValues(Map<String, Object> variables) {

        variables.put("@time", System.currentTimeMillis());
        variables.put("@step", 0);

        Calendar calendar = Calendar.getInstance();

        variables.put("@second", calendar.get(Calendar.SECOND));
        variables.put("@minute", calendar.get(Calendar.MINUTE));
        variables.put("@hour", calendar.get(Calendar.HOUR));
        variables.put("@day", calendar.get(Calendar.DAY_OF_MONTH));
        variables.put("@month", calendar.get(Calendar.MONTH));
        variables.put("@year", calendar.get(Calendar.YEAR));

        variables.put("@datetime", new Date().toString());
        variables.put("@players", Groups.player);

    }

    public void next() {
        next(current.nextId());
    }

    public void next(String nextId) {
        if (step > MAX_STEP) {
            throw new StackOverflowError("Max stack exceeded");
        }

        if (nextId == null) {
            Log.debug("No next node to execute, step: @, current: @", step, current.getId());
            return;
        }

        var nextNode = Workflow.getNodes().get(nextId);

        if (nextNode == null) {
            throw new IllegalStateException("Node not found, id: " + nextId);
        }

        try {
            nextNode.execute(new WorkflowEmitEvent(step + 1, nextNode, context, variables));
        } catch (Exception e) {
            Log.err(e);
            HashMap<String, Object> error = new HashMap<>();
            error.put("message", e.getMessage());
            Workflow.sendWorkflowEvent(new WorkflowEvent<>(nextNode.getId(), "ERROR", error));
        }
        Workflow.sendWorkflowEvent(new WorkflowEvent<>(nextNode.getId(), "EMIT", null));
    }

    public static WorkflowEmitEvent create(WorkflowNode current, Workflow context) {
        Workflow.sendWorkflowEvent(new WorkflowEvent<>(current.getId(), "EMIT", null));

        return new WorkflowEmitEvent(0, current, context, new HashMap<>());
    }
}
