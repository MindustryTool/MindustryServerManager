package events;

import java.util.UUID;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Accessors(chain = true)
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
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
