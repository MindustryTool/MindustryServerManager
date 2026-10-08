package common.server;

import java.util.List;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ServerCommand {
    public String text;
    public String paramText;
    public String description;
    public List<CommandParam> params;
}
