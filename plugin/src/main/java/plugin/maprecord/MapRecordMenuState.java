package plugin.maprecord;

import java.util.List;

public record MapRecordMenuState(
    String mapName,
    String gamemode,
    List<MapRecord> records
) {}
