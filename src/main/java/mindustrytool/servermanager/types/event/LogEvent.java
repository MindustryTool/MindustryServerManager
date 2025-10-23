package mindustrytool.servermanager.types.event;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true, fluent = true)
public class LogEvent extends BaseEvent {
    private String data;
    private LogLevel level;

    public static enum LogLevel {
        INFO,
        ERROR
    }

    public LogEvent(UUID serverId) {
        super(serverId, "log");
    }

    public static LogEvent info(UUID serverId, String data) {
        return new LogEvent(serverId).level(LogLevel.INFO).data(data);
    }

    public static LogEvent error(UUID serverId, String data) {
        return new LogEvent(serverId).level(LogLevel.ERROR).data(data);
    }

}
