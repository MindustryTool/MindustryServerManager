package plugin.database.schema;

import plugin.orm.table.Column;
import plugin.orm.table.Table;

public final class MapRatings {
    public static final Table<Void> TABLE = Table.of("map_ratings");
    public static final Column<String> MAP_NAME = TABLE.column("map_name", String.class).primaryKey();
    public static final Column<Integer> STAR_1 = TABLE.column("star_1", Integer.class).notNull().defaultValue(0);
    public static final Column<Integer> STAR_2 = TABLE.column("star_2", Integer.class).notNull().defaultValue(0);
    public static final Column<Integer> STAR_3 = TABLE.column("star_3", Integer.class).notNull().defaultValue(0);
    public static final Column<Integer> STAR_4 = TABLE.column("star_4", Integer.class).notNull().defaultValue(0);
    public static final Column<Integer> STAR_5 = TABLE.column("star_5", Integer.class).notNull().defaultValue(0);
    public static final Column<Integer> VOTES = TABLE.column("votes", Integer.class).notNull().defaultValue(0);
    public static final Column<Float> RATING = TABLE.column("rating", Float.class).notNull().defaultValue(0.0f);

    private MapRatings() {
    }
}
