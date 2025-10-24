package plugin.workflow;

import lombok.Data;

@Data
public class NodeData {
    private String id;
    private String name;
    private WorkflowNodeState state;
}
