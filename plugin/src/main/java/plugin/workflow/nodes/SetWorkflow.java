package plugin.workflow.nodes;

import plugin.workflow.WorkflowEmitEvent;
import plugin.workflow.WorkflowGroup;

public class SetWorkflow extends WorkflowNode {

    private final WorkflowField<String, Void> nameField = new WorkflowField<String, Void>("name")
            .consume(new FieldConsumer<>(String.class)
                    .defaultValue("a"));

    private final WorkflowField valueField = new WorkflowField("value")
            .consume(new FieldConsumer<Object>(Object.class)
                    .defaultValue("0"));

    public SetWorkflow() {
        super("Set", WorkflowGroup.BASE, 1);

        defaultOneOutput();
    }

    @Override
    public void execute(WorkflowEmitEvent event) {
        event.putValue(nameField.getConsumer().asString(event), valueField.getConsumer().consume(event));

        event.next();
    }
}
