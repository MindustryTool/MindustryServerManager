package plugin.workflow;

import lombok.Data;

@Data
public class NodeDataField {
    private String name;

    private NodeDataProduce produce;
    private NodeDataConsumer consumer;
}
