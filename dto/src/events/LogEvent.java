package events;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class LogEvent extends BaseEvent {
    private String data;
    private LogLevel level;

    public static enum LogLevel {
        info,
        warning,
        error
    }

    public LogEvent(UUID serverId) {
        super(serverId, "log");
    }

    public static LogEvent info(UUID serverId, String data) {
        return new LogEvent(serverId).setLevel(LogLevel.info).setData(data);
    }

    public static LogEvent error(UUID serverId, String data) {
        return new LogEvent(serverId).setLevel(LogLevel.error).setData(data);
    }

}
