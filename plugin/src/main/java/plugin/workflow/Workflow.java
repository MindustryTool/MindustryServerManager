package plugin.workflow;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import arc.files.Fi;
import arc.func.Cons2;
import arc.struct.Seq;
import arc.util.Log;
import io.javalin.http.sse.SseClient;
import lombok.Getter;
import mindustry.Vars;
import plugin.ServerController;
import plugin.utils.JsonUtils;
import plugin.workflow.errors.WorkflowError;
import plugin.workflow.expressions.ExpressionParser;
import plugin.workflow.nodes.BinaryOperationWorkflow;
import plugin.workflow.nodes.DisplayLabelWorkflow;
import plugin.workflow.nodes.EventListenerWorkflow;
import plugin.workflow.nodes.IfWorkflow;
import plugin.workflow.nodes.IntervalWorkflow;
import plugin.workflow.nodes.RandomWorkflow;
import plugin.workflow.nodes.SendChatWorkflow;
import plugin.workflow.nodes.SetWorkflow;
import plugin.workflow.nodes.UnaryOperationWorkflow;
import plugin.workflow.nodes.WaitWorkflow;
import plugin.workflow.nodes.WorkflowNode;
import plugin.type.WorkflowContext;

public class Workflow {
    private final HashMap<Object, Seq<Cons2<?, Boolean>>> events = new HashMap<>();

    @Getter
    private final ExpressionParser expressionParser = new ExpressionParser();

    @Getter
    private final HashMap<String, WorkflowNode> nodeTypes = new HashMap<>();
    @Getter
    private final HashMap<String, WorkflowNode> nodes = new HashMap<>();
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();

    private final Fi WORKFLOW_DIR = Vars.dataDirectory.child("workflow");
    private final Fi WORKFLOW_FILE = WORKFLOW_DIR.child("workflow.json");
    private final Fi WORKFLOW_DATA_FILE = WORKFLOW_DIR.child("workflow_data.json");

    @Getter
    public WorkflowContext workflowContext;

    private final Queue<SseClient> workflowEventConsumers = new ConcurrentLinkedQueue<>();

    private final WeakReference<ServerController> context;

    public Workflow(WeakReference<ServerController> context) {
        this.context = context;
    }

    public Queue<SseClient> getWorkflowEventConsumers() {
        return workflowEventConsumers;
    }

    public void sendWorkflowEvent(WorkflowEvent event) {
        try {
            workflowEventConsumers.forEach(consumer -> consumer.sendEvent(event));
        } catch (Exception e) {
            Log.err("Error sending workflow event", e);
        }
    }

    public void init() {
        try {
            register(new EventListenerWorkflow());
            register(new SendChatWorkflow());
            register(new IntervalWorkflow());
            register(new WaitWorkflow());
            register(new RandomWorkflow());
            register(new IfWorkflow());
            register(new DisplayLabelWorkflow());
            register(new SetWorkflow());

            expressionParser.BINARY_OPERATORS
                    .forEach((_ignore, operator) -> register(new BinaryOperationWorkflow(operator)));

            expressionParser.UNARY_OPERATORS
                    .forEach((_ignore, operator) -> register(new UnaryOperationWorkflow(operator)));

            WORKFLOW_DIR.mkdirs();
            WORKFLOW_FILE.file().createNewFile();
            WORKFLOW_DATA_FILE.file().createNewFile();

            context.get().BACKGROUND_SCHEDULER.scheduleWithFixedDelay(
                    () -> {
                        try {
                            workflowEventConsumers.forEach(client -> client.sendComment("heartbeat"));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, 0, 2,
                    TimeUnit.SECONDS);

            loadWorkflowFromFile();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public JsonNode readWorkflowData() {
        return JsonUtils.readJson(WORKFLOW_DATA_FILE.readString());
    }

    public void writeWorkflowData(JsonNode data) {
        WORKFLOW_DATA_FILE.writeString(JsonUtils.toJsonString(data));
    }

    private void loadWorkflowFromFile() {
        String content = WORKFLOW_FILE.readString();
        if (!content.trim().isEmpty()) {
            workflowContext = JsonUtils.readJsonAsClass(content, WorkflowContext.class);
            load(workflowContext);
        }
    }

    private void writeWorkflowToFile() {
        WORKFLOW_FILE.writeString(JsonUtils.toJsonString(workflowContext));
    }

    private void register(WorkflowNode node) {
        if (nodeTypes.containsKey(node.getName())) {
            throw new IllegalStateException("Node already registered: " + node.getName());
        }

        nodeTypes.put(node.getName(), node);
    }

    public void clear() {
        events.clear();
        nodeTypes.clear();
        nodes.clear();

        scheduledTasks.forEach(task -> {
            if (!task.isCancelled()) {
                task.cancel(true);
            }
        });

        scheduledTasks.clear();

        Log.info("Workflow unloaded");
    }

    public void load(WorkflowContext workflowContext) {
        Log.info("Load workflow workflowContext" + workflowContext);

        nodes.values().forEach(node -> node.unload(this));
        nodes.clear();
        events.clear();

        scheduledTasks.forEach(task -> {
            if (!task.isCancelled()) {
                task.cancel(true);
            }
        });
        scheduledTasks.clear();

        this.workflowContext = workflowContext;
        writeWorkflowToFile();

        for (var data : workflowContext.getNodes()) {
            var node = nodeTypes.get(data.getName());

            if (node == null) {
                throw new WorkflowError("Node type not found: " + data.getName());
            }

            var constructors = node.getClass().getConstructors();

            if (constructors == null || constructors.length == 0) {
                throw new WorkflowError("No constructor for node: " + node.getClass().getSimpleName());
            }

            try {
                var newNode = (WorkflowNode) constructors[0].newInstance();

                newNode.setId(data.getId());

                data.getState().getOutputs().entrySet().forEach(entry -> {
                    var name = entry.getKey();
                    var nextId = entry.getValue();
                    var newOutput = newNode.getOutputs()
                            .stream()
                            .filter(nn -> nn.getName().equals(name))
                            .findFirst()
                            .orElseThrow(() -> new WorkflowError(
                                    "Node output not found: " + name + " on node: " + node.getName()));

                    newOutput.setNextId(nextId);
                });

                data.getState().getFields().entrySet().forEach(entry -> {
                    var name = entry.getKey();
                    var value = entry.getValue();
                    var newOutput = newNode.getFields()
                            .stream()
                            .filter(nn -> nn.getName().equals(name))
                            .findFirst()
                            .orElseThrow(() -> new WorkflowError(
                                    "Node fields not found: " + name + " on node: "
                                            + node.getName()));

                    if (newOutput.getConsumer().isRequired() && value.getConsumer() == null) {
                        throw new WorkflowError("Node fields value is required: " + name
                                + " on node: " + node.getName());
                    }

                    newOutput.getConsumer().setValue(value.getConsumer());

                    if (value.getVariableName() != null) {
                        newOutput.getProducer().setVariableName(value.getVariableName());
                    }
                });

                nodes.put(newNode.getId(), newNode);

                Log.debug("Node loaded: " + newNode.getName() + ":" + newNode.getId() + " "
                        + data.getState().getOutputs());

            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new WorkflowError("Can not create new node: " + node.getClass().getSimpleName(), e);
            }
        }

        for (var node : nodes.values()) {
            node.init(this);
        }

        Log.info("Context loaded");
    }

    public <T> Cons2<T, Boolean> on(Class<T> type, Cons2<T, Boolean> listener) {
        events.computeIfAbsent(type, (_ignore) -> new Seq<>(Cons2.class)).add(listener);

        return listener;
    }

    public <T> boolean remove(Class<T> type, Cons2<T, Boolean> listener) {
        return events.computeIfAbsent(type, (_ignore) -> new Seq<>(Cons2.class)).remove(listener);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public <T extends Enum<T>> void fire(Enum<T> type, boolean before) {
        Seq<Cons2<?, Boolean>> listeners = events.get(type);

        if (listeners != null) {
            int len = listeners.size;
            Cons2[] items = listeners.items;
            for (int i = 0; i < len; i++) {
                items[i].get(type, before);
            }
        }
    }

    /** Fires a non-enum event by class. */
    public <T> void fire(T type, boolean before) {
        fire(type.getClass(), type, before);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public <T> void fire(Class<?> ctype, T type, boolean before) {
        Seq<Cons2<?, Boolean>> listeners = events.get(ctype);

        if (listeners != null) {
            int len = listeners.size;
            Cons2[] items = listeners.items;
            for (int i = 0; i < len; i++) {
                items[i].get(type, before);
            }
        }
    }

    private void tryRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            Log.err("Error running task", e);
        }
    }

    public void scheduleAtFixedRate(Runnable runnable, long delay, long period) {
        Log.debug("Schedule task at fixed rate: " + runnable.getClass().getName() +
                " delay: " + delay +
                " period: " + period);

        scheduledTasks
                .add(context.get().BACKGROUND_SCHEDULER.scheduleAtFixedRate(() -> tryRun(runnable), delay, period,
                        TimeUnit.SECONDS));
    }

    public void scheduleWithFixedDelay(Runnable runnable, long initialDelay, long delay) {
        Log.debug("Schedule task with fixed delay: " +
                runnable.getClass().getName() +
                " initialDelay: " + initialDelay +
                " delay: " + delay);

        scheduledTasks
                .add(context.get().BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> tryRun(runnable), initialDelay,
                        delay,
                        TimeUnit.SECONDS));
    }

    public void schedule(Runnable runnable, long delay) {
        Log.debug("Schedule task: " + runnable.getClass().getName() + " delay: " + delay);
        scheduledTasks
                .add(context.get().BACKGROUND_SCHEDULER.schedule(() -> tryRun(runnable), delay, TimeUnit.SECONDS));
    }

}
