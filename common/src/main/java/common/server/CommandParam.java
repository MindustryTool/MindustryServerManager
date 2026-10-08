package common.server;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class CommandParam {
    public String name;
    public boolean optional;
    public boolean variadic;
}
