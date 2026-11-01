package plugin.database.schema;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class BanLogs {
    public static final Table<Void> TABLE = Table.of("ban_logs");
    public static final Column<Long> ID = TABLE.column("id", Long.class).primaryKey();
    public static final Column<String> ACTION = TABLE.column("action", String.class).notNull();
    public static final Column<String> USER_ID = TABLE.column("user_id", String.class);
    public static final Column<String> UUID = TABLE.column("uuid", String.class);
    public static final Column<String> IP = TABLE.column("ip", String.class);
    public static final Column<String> NAME = TABLE.column("name", String.class);
    public static final Column<String> ACTOR = TABLE.column("actor", String.class).notNull();
    public static final Column<String> REASON = TABLE.column("reason", String.class).notNull();
    public static final Column<Long> CREATED_AT = TABLE.column("created_at", Long.class).notNull();

    private BanLogs() {
    }
}
