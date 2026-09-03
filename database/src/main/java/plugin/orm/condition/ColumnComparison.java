package plugin.orm.condition;

import java.util.List;

import plugin.orm.table.Column;

public final class ColumnComparison implements Condition {
    private final Column<?> left;
    private final String operator;
    private final Column<?> right;

    public ColumnComparison(Column<?> left, String operator, Column<?> right) {
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    @Override
    public void appendSql(StringBuilder sql, List<Object> parameters) {
        sql.append(left.qualifiedName()).append(' ').append(operator).append(' ').append(right.qualifiedName());
    }
}
