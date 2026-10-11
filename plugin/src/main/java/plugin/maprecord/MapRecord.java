package plugin.maprecord;

import java.util.List;

public record MapRecord(
    long id,
    String mapName,
    String gamemode,
    long durationMs,
    List<MapRecordPlayer> players,
    long createdAt
) {}
