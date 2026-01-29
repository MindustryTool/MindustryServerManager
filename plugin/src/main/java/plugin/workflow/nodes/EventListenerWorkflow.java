package plugin.workflow.nodes;

import mindustry.game.EventType;
import plugin.PluginEvents;
import plugin.workflow.Workflow;
import plugin.workflow.WorkflowEmitEvent;
import plugin.workflow.WorkflowGroup;

public class EventListenerWorkflow extends WorkflowNode {
    private final WorkflowField<Class<?>, Void> classField = new WorkflowField<Class, Void>("class")
            .consume(new FieldConsumer<Class>(Class.class))
            .produce(new FieldProducer("event", Class.class));

    {
        preInit();
    }

    private void preInit() {
        for (var clazz : EventType.class.getDeclaredClasses()) {
            classField.getConsumer().option(clazz.getSimpleName(), clazz.getName(), clazz);
        }
    }

    public EventListenerWorkflow() {
        super("EventListener", WorkflowGroup.EMITTER, 0);

        defaultOneOutput();
    }

    @Override
    public void init(Workflow context) {
        Class<?> eventClass = classField.getConsumer().asClass();

        PluginEvents.on(eventClass, (event) -> {
            WorkflowEmitEvent.create(this, context)
                    .putValue(classField.getProducer().getVariableName(), event)
                    .next();
        });
    }
}
