package plugin.workflow.nodes;

import mindustry.game.EventType;
import plugin.workflow.Workflow;
import plugin.workflow.WorkflowEmitEvent;
import plugin.workflow.WorkflowGroup;

public class EventListenerWorkflow extends WorkflowNode {
    private final WorkflowField<Boolean, Void> beforeField = new WorkflowField<Boolean, Void>("before")
            .consume(new FieldConsumer<>(Boolean.class)
                    .defaultValue(true));

    private final WorkflowField<Class<?>, Void> classField = new WorkflowField<Class, Void>("class")
            .consume(new FieldConsumer<>(Class.class))
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

        context.on(eventClass, (event, before) -> {
            if (before == this.beforeField.getConsumer().asBoolean()) {
                WorkflowEmitEvent.create(this, context)
                        .putValue(classField.getProducer().getVariableName(), event)
                        .next();
            }
        });
    }
}
