package plugin.orm.table;

public final class Table<T> {
    private final String name;

    private Table(String name) {
        this.name = name;
    }

    public static <T> Table<T> of(String name) {
        return new Table<>(name);
    }

    public <V> Column<V> column(String name, Class<V> type) {
        return new Column<>(this, name, type);
    }

    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
