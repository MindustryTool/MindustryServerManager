package plugin.type;

import java.util.function.Consumer;

import arc.util.CommandHandler.CommandResponse;
import lombok.Data;

@Data
public class PrevCommand {
    private final String command;
    private final Consumer<CommandResponse> callback;
}
