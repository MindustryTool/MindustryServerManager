package plugin.orm.condition;

import java.util.List;

import plugin.orm.table.Column;

public final class LikeCondition implements Condition {
    private final Column<?> column;
    private final String pattern;

public LikeCondition(Column<?> column, String pattern) {
        this.column = column;
        this.pattern = pattern;
    }

    @Override
    public void appendSql(StringBuilder sql, List<Object> parameters) {
        sql.append(column.qualifiedName()).append(" LIKE ?");
        parameters.add(pattern);
    }
}
