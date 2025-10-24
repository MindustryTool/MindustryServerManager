package plugin.workflow;

import lombok.Data;

@Data
public class WorkflowNodeStateField {
    private String consumer;
    private String producer;
    private String variableName;
}
