package plugin.orm.table;

public final class Order {
    private final Column<?> column;
    private final boolean ascending;

    Order(Column<?> column, boolean ascending) {
        this.column = column;
        this.ascending = ascending;
    }

    public Column<?> column() {
        return column;
    }

    public boolean ascending() {
        return ascending;
    }
}
