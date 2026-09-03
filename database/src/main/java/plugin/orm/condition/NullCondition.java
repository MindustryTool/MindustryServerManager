package plugin.orm.condition;

import java.util.List;

import plugin.orm.table.Column;

public final class NullCondition implements Condition {
    private final Column<?> column;
    private final boolean negated;

public NullCondition(Column<?> column, boolean negated) {
        this.column = column;
        this.negated = negated;
    }

    @Override
    public void appendSql(StringBuilder sql, List<Object> parameters) {
        sql.append(column.qualifiedName()).append(negated ? " IS NOT NULL" : " IS NULL");
    }
}
