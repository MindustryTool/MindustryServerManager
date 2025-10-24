package plugin.workflow.nodes;

import plugin.workflow.WorkflowUnit;
import plugin.workflow.WorkflowEmitEvent;
import plugin.workflow.WorkflowGroup;

public class WaitWorkflow extends WorkflowNode {
    private final WorkflowField secondField = new WorkflowField("second")
            .consume(new FieldConsumer<>(Long.class)
                    .unit(WorkflowUnit.SECOND)
                    .defaultValue(1000L));

    public WaitWorkflow() {
        super("Wait", WorkflowGroup.FLOW, 1);

        defaultOneOutput();
    }

    @Override
    public void execute(WorkflowEmitEvent event) {
        event.getContext().schedule(() -> {
            event.next();
        }, secondField.getConsumer().asLong());
    }

}
