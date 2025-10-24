package plugin.workflow;

import lombok.Data;
import java.util.HashMap;

@Data
public class WorkflowNodeState {
    private HashMap<String, String> outputs = new HashMap<>();
    private HashMap<String, WorkflowNodeStateField> fields = new HashMap<>();
}
