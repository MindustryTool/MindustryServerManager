package plugin.database.schema;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class Sessions {
    public static final Table<Void> TABLE = Table.of("sessions");
    public static final Column<String> UUID = TABLE.column("uuid", String.class);
    public static final Column<String> DATA = TABLE.column("data", String.class);
    public static final Column<Long> TOTAL_EXP = TABLE.column("totalExp", Long.class);

    private Sessions() {
    }
}
