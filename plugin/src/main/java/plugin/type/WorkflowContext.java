package plugin.type;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import plugin.workflow.NodeData;

@Data
public class WorkflowContext {
    private List<NodeData> nodes = new ArrayList<>();
    private Long createdAt = System.currentTimeMillis();
}
