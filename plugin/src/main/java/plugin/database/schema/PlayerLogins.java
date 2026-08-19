package plugin.database.schema;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class PlayerLogins {
    public static final Table<Void> TABLE = Table.of("player_logins");
    public static final Column<String> UUID = TABLE.column("uuid", String.class).primaryKey();
    public static final Column<String> LAST_LOGIN_DATE = TABLE.column("last_login_date", String.class).notNull();

    private PlayerLogins() {
    }
}