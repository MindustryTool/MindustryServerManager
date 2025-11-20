package plugin.workflow.nodes;

import mindustry.gen.Call;
import mindustry.gen.Player;
import plugin.workflow.WorkflowEmitEvent;
import plugin.workflow.WorkflowGroup;
import plugin.workflow.WorkflowUnit;

public class DisplayLabelWorkflow extends WorkflowNode {
    private final WorkflowField<Player, Void> playerField = new WorkflowField<Player, Void>("player")
            .consume(new FieldConsumer<>(Player.class).notRequired());

    private final WorkflowField<String, Void> messageField = new WorkflowField<String, Void>("message")
            .consume(new FieldConsumer<>(String.class)
                    .defaultValue("Hello"));

    private final WorkflowField<Float, Void> xField = new WorkflowField<Float, Void>("x")
            .consume(new FieldConsumer<>(Float.class)
                    .defaultValue(0f));

    private final WorkflowField<Float, Void> yField = new WorkflowField<Float, Void>("y")
            .consume(new FieldConsumer<>(Float.class)
                    .defaultValue(0f));

    private final WorkflowField<Float, Void> durationField = new WorkflowField<Float, Void>("duration")
            .consume(new FieldConsumer<>(Float.class)
                    .unit(WorkflowUnit.SECOND)
                    .defaultValue(0f));

    public DisplayLabelWorkflow() {
        super("DisplayLabel", WorkflowGroup.DISPLAY, 1);

        defaultOneOutput();
    }

    @Override
    public void execute(WorkflowEmitEvent event) {
        Player player = playerField.getConsumer().consume(event);
        String message = messageField.getConsumer().asString(event);
        Float x = xField.getConsumer().asFloat(event);
        Float y = yField.getConsumer().asFloat(event);
        Float duration = durationField.getConsumer().asFloat(event);

        if (player == null) {
            Call.label(message, duration, x, y);
        } else {
            Call.label(player.con, message, duration, x, y);
        }
    }
}
