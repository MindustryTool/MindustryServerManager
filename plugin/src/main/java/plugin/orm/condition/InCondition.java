package plugin.orm.condition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import plugin.orm.table.Column;

public final class InCondition implements Condition {
    private final Column<?> column;
    private final boolean negated;
    private final List<Object> values;

public InCondition(Column<?> column, boolean negated, Collection<?> values) {
        this.column = column;
        this.negated = negated;
        this.values = new ArrayList<>(values);
    }

    @Override
    public void appendSql(StringBuilder sql, List<Object> parameters) {
        if (values.isEmpty()) {
            sql.append(negated ? "1 = 1" : "1 = 0");
            return;
        }

        sql.append(column.qualifiedName()).append(negated ? " NOT IN (" : " IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
            parameters.add(values.get(i));
        }
        sql.append(')');
    }
}
