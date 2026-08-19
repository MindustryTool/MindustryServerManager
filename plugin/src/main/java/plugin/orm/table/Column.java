package plugin.orm.table;

import java.util.Collection;
import java.util.List;

import plugin.orm.condition.ColumnComparison;
import plugin.orm.condition.Comparison;
import plugin.orm.condition.Condition;
import plugin.orm.condition.InCondition;
import plugin.orm.condition.LikeCondition;
import plugin.orm.condition.NullCondition;

public final class Column<T> {
    private final Table<?> table;
    private final String name;
    private final Class<T> type;

    Column(Table<?> table, String name, Class<T> type) {
        this.table = table;
        this.name = name;
        this.type = type;
    }

    public Table<?> table() {
        return table;
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    public String qualifiedName() {
        return table.name() + "." + name;
    }

    public Condition eq(T value) {
        return value == null ? new NullCondition(this, false) : new Comparison(this, "=", value);
    }

    public Condition eqColumn(Column<?> other) {
        return new ColumnComparison(this, "=", other);
    }

    public Condition ne(T value) {
        return value == null ? new NullCondition(this, true) : new Comparison(this, "<>", value);
    }

    public Condition neColumn(Column<?> other) {
        return new ColumnComparison(this, "<>", other);
    }

    public Condition gt(T value) {
        return new Comparison(this, ">", value);
    }

    public Condition gte(T value) {
        return new Comparison(this, ">=", value);
    }

    public Condition lt(T value) {
        return new Comparison(this, "<", value);
    }

    public Condition lte(T value) {
        return new Comparison(this, "<=", value);
    }

    public Condition in(Collection<? extends T> values) {
        return new InCondition(this, false, values);
    }

    @SafeVarargs
    public final Condition in(T... values) {
        return new InCondition(this, false, List.of(values));
    }

    public Condition notIn(Collection<? extends T> values) {
        return new InCondition(this, true, values);
    }

    @SafeVarargs
    public final Condition notIn(T... values) {
        return new InCondition(this, true, List.of(values));
    }

    public Condition like(String pattern) {
        return new LikeCondition(this, pattern);
    }

    public Condition isNull() {
        return new NullCondition(this, false);
    }

    public Condition isNotNull() {
        return new NullCondition(this, true);
    }

    public Order asc() {
        return new Order(this, true);
    }

    public Order desc() {
        return new Order(this, false);
    }

    @Override
    public String toString() {
        return qualifiedName();
    }
}
