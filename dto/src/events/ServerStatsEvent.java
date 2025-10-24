package events;

import java.util.List;
import java.util.UUID;

import dto.StatsDto;

public class ServerStatsEvent extends BaseEvent {

    public final List<StatsDto> stats;

    public ServerStatsEvent(UUID serverId, List<StatsDto> stats) {
        super(serverId, "server-stats");
        this.stats = stats;
    }
}
