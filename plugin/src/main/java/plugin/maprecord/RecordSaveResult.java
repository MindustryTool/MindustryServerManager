package plugin.maprecord;

public record RecordSaveResult(
    MapRecord record,
    int rank,
    boolean isNewBest
) {}
