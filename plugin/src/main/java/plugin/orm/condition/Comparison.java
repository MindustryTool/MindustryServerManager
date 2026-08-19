package plugin.orm.condition;

import java.util.List;

import plugin.orm.table.Column;

public final class Comparison implements Condition {
    private final Column<?> column;
    private final String operator;
    private final Object value;

public Comparison(Column<?> column, String operator, Object value) {
        this.column = column;
        this.operator = operator;
        this.value = value;
    }

    @Override
    public void appendSql(StringBuilder sql, List<Object> parameters) {
        sql.append(column.qualifiedName()).append(' ').append(operator).append(" ?");
        parameters.add(value);
    }
}
