package plugin.commands;

import lombok.Getter;
import plugin.annotations.Param;

@Getter
public class ParamException extends Exception {
    private final Param param;
    private final String value;

    public ParamException(Param param, String value) {
        super(String.format("Param %s with value %s is invalid", param.name(), value));
        this.param = param;
        this.value = value;
    }
}
