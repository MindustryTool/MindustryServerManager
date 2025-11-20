package plugin.workflow;

import java.util.Map;
import java.util.UUID;

import lombok.Data;

@Data
public class WorkflowEvent<T extends Map<?, ?>> {
    private String id;
    private String nodeId;
    private String name;
    private T value;
    private Long createdAt;

    public WorkflowEvent(String nodeId, String name, T value) {
        this.nodeId = nodeId;
        this.name = name;
        this.value = value;

        id = UUID.randomUUID().toString();
        createdAt = System.currentTimeMillis();
    }
}
