package plugin.workflow.nodes;

import plugin.workflow.WorkflowEmitEvent;
import plugin.workflow.WorkflowGroup;
import plugin.workflow.expressions.UnaryOperator;

public class UnaryOperationWorkflow extends WorkflowNode {
    private final WorkflowField<Double, Void> aField = new WorkflowField<Double, Void>("a")
            .setConsumer(new FieldConsumer<>(Double.class).defaultValue(0d));

    private final WorkflowField<Void, Double> resultField = new WorkflowField<Void, Double>("result")
            .produce(new FieldProducer<Double>("result", Double.class));

    private final UnaryOperator operator;

    public UnaryOperationWorkflow(UnaryOperator operator) {
        super(operator.getName(), WorkflowGroup.OPERATION, 1);
        this.operator = operator;

        defaultOneOutput();
    }

    @Override
    public void execute(WorkflowEmitEvent event) {
        Double a = aField.getConsumer().asDouble(event);

        event.putValue(resultField.getProducer().getVariableName(), operator.getFunction().apply(a));

        event.next();
    }
}
