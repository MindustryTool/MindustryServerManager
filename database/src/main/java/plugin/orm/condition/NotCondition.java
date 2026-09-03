package plugin.orm.condition;

import java.util.List;

public final class NotCondition implements Condition {
    private final Condition operand;

public NotCondition(Condition operand) {
        this.operand = operand;
    }

    @Override
    public void appendSql(StringBuilder sql, List<Object> parameters) {
        sql.append("NOT (");
        operand.appendSql(sql, parameters);
        sql.append(')');
    }
}
