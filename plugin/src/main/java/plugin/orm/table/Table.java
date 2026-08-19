package plugin.orm.table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import plugin.orm.OrmException;

public final class Table<T> {
    private final String name;
    private final Map<String, Column<?>> columns = new LinkedHashMap<>();

    private Table(String name) {
        this.name = name;
    }

    public static <T> Table<T> of(String name) {
        return new Table<>(name);
    }

    public <V> Column<V> column(String name, Class<V> type) {
        Column<?> existing = columns.get(name);
        if (existing != null) {
            throw new OrmException("Column '" + name + "' already exists on table '" + this.name + "'");
        }
        Column<V> column = new Column<>(this, name, type);
        columns.put(name, column);
        return column;
    }

    void register(Column<?> column) {
        columns.put(column.name(), column);
    }

    public List<Column<?>> columns() {
        return List.copyOf(columns.values());
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
