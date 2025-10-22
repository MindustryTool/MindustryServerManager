package mindustrytool.servermanager.types.event;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class LogEvent extends ServerEvent {
    private String data;
    private LogLevel level;


    public static enum LogLevel {
        INFO,
        ERROR
    }
}
