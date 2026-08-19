package plugin.orm.table;

import java.util.ArrayList;
import java.util.List;

public final class Table<T> {
    private final String name;
    private final List<Column<?>> columns = new ArrayList<>();

    private Table(String name) {
        this.name = name;
    }

    public static <T> Table<T> of(String name) {
        return new Table<>(name);
    }

    @SuppressWarnings("unchecked")
    public <V> Column<V> column(String name, Class<V> type) {
        for (Column<?> existing : columns) {
            if (existing.name().equals(name)) {
                return (Column<V>) existing;
            }
        }
        Column<V> column = new Column<>(this, name, type);
        columns.add(column);
        return column;
    }

    public List<Column<?>> columns() {
        return List.copyOf(columns);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
