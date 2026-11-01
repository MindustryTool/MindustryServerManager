package plugin.database.schema;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class BannedUsers {
    public static final Table<Void> TABLE = Table.of("banned_users");
    public static final Column<String> USER_ID = TABLE.column("user_id", String.class).primaryKey();
    public static final Column<String> REASON = TABLE.column("reason", String.class).notNull();
    public static final Column<String> ACTOR = TABLE.column("actor", String.class);
    public static final Column<Long> CREATED_AT = TABLE.column("created_at", Long.class).notNull();

    private BannedUsers() {
    }
}
