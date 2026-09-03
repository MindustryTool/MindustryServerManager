package plugin.orm.condition;

import java.util.List;

public final class OrCondition implements Condition {
    private final Condition left;
    private final Condition right;

public OrCondition(Condition left, Condition right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public void appendSql(StringBuilder sql, List<Object> parameters) {
        appendOperand(sql, parameters, left);
        sql.append(" OR ");
        appendOperand(sql, parameters, right);
    }

    private static void appendOperand(StringBuilder sql, List<Object> parameters, Condition operand) {
        if (operand instanceof AndCondition || operand instanceof NotCondition) {
            sql.append('(');
            operand.appendSql(sql, parameters);
            sql.append(')');
        } else {
            operand.appendSql(sql, parameters);
        }
    }
}
