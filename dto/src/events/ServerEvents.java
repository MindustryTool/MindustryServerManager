package events;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import arc.util.Log;
import dto.PlayerDto;
import dto.ServerStateDto;
import enums.NodeRemoveReason;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

public class ServerEvents {
    private static final HashMap<String, Class<?>> eventTypeMap = new HashMap<>();

    public static HashMap<String, Class<?>> getEventMap() {
        if (eventTypeMap.size() == 0) {
            for (var clazz : ServerEvents.class.getDeclaredClasses()) {
                if (clazz.isInstance(clazz)) {
                    String className = clazz.getSimpleName()
                            .replace("Event", "")
                            .replaceAll("([a-z])([A-Z]+)", "$1-$2")
                            .toLowerCase();

                    eventTypeMap.put(className, clazz);
                    Log.info("Register @ events", className);
                }
            }
        }

        return eventTypeMap;
    }

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = false)
    @NoArgsConstructor
    public static class ServerStateEvent extends BaseEvent {

        public List<ServerStateDto> state;

        public ServerStateEvent(UUID serverId, List<ServerStateDto> state) {
            super(serverId, "server-state");
            this.state = state;
        }
    }

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = false)
    @NoArgsConstructor
    public static class StartEvent extends BaseEvent {

        public StartEvent(UUID serverId) {
            super(serverId, "start");
        }
    }

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = false)
    @NoArgsConstructor
    public static class DisconnectEvent extends BaseEvent {
        public DisconnectEvent(UUID serverId) {
            super(serverId, "disconnect");
        }
    }

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = false)
    @NoArgsConstructor
    public static class StopEvent extends BaseEvent {

        private String reason;

        public StopEvent(UUID serverId, NodeRemoveReason reason) {
            super(serverId, "stop");
            this.reason = reason.name();
        }

        public StopEvent(UUID serverId, String reason) {
            super(serverId, "stop");
            this.reason = reason;
        }
    }

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = false)
    @NoArgsConstructor
    public static class LogEvent extends BaseEvent {
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

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = false)
    @NoArgsConstructor
    public static class ChatEvent extends BaseEvent {
        private String data;

        public ChatEvent(UUID serverId, String data) {
            super(serverId, "chat");
            this.data = data;
        }
    }

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = false)
    @NoArgsConstructor
    public static class PlayerJoinEvent extends BaseEvent {
        private PlayerDto player;

        public PlayerJoinEvent(UUID serverId, PlayerDto player) {
            super(serverId, "player-join");
            this.player = player;
        }
    }

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = false)
    @NoArgsConstructor
    public static class PlayerLeaveEvent extends BaseEvent {
        private PlayerDto player;

        public PlayerLeaveEvent(UUID serverId, PlayerDto player) {
            super(serverId, "player-leave");
            this.player = player;
        }
    }
}
