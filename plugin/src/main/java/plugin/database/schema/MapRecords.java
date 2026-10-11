package plugin.database.schema;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class MapRecords {
    public static final Table<Void> TABLE = Table.of("map_records");
    public static final Column<Long> ID = TABLE.column("id", Long.class).primaryKey();
    public static final Column<String> MAP_NAME = TABLE.column("map_name", String.class).notNull();
    public static final Column<String> GAMEMODE = TABLE.column("gamemode", String.class).notNull();
    public static final Column<Long> DURATION_MS = TABLE.column("duration_ms", Long.class).notNull();
    public static final Column<String> PLAYERS = TABLE.column("players", String.class).notNull();
    public static final Column<Long> CREATED_AT = TABLE.column("created_at", Long.class).notNull();

    private MapRecords() {
    }
}
