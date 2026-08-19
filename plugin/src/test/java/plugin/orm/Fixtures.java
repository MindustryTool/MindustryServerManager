package plugin.orm;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class Fixtures {

    public static final Table<Object> USERS = Table.of("users");
    public static final Column<Long> USERS_ID = USERS.column("id", Long.class);
    public static final Column<String> USERS_NAME = USERS.column("name", String.class);
    public static final Column<Boolean> USERS_ACTIVE = USERS.column("active", Boolean.class);
    public static final Column<Long> USERS_SERVER_ID = USERS.column("server_id", Long.class);

    public static final Table<Object> SERVERS = Table.of("servers");
    public static final Column<Long> SERVERS_ID = SERVERS.column("id", Long.class);
    public static final Column<String> SERVERS_NAME = SERVERS.column("name", String.class);

    public static final Table<Object> SESSIONS = Table.of("sessions");
    public static final Column<String> SESSIONS_UUID = SESSIONS.column("uuid", String.class);
    public static final Column<String> SESSIONS_DATA = SESSIONS.column("data", String.class);
    public static final Column<Long> SESSIONS_TOTAL_EXP = SESSIONS.column("totalExp", Long.class);

    public static final Table<Object> PLAYER_LOGINS = Table.of("player_logins");
    public static final Column<String> PLAYER_LOGINS_UUID = PLAYER_LOGINS.column("uuid", String.class);
    public static final Column<String> PLAYER_LOGINS_DATE = PLAYER_LOGINS.column("last_login_date", String.class);

    private Fixtures() {
    }
}
