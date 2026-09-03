package plugin.orm.condition;

import java.util.List;

public interface Condition {

    void appendSql(StringBuilder sql, List<Object> parameters);

    default Condition and(Condition other) {
        return new AndCondition(this, other);
    }

    default Condition or(Condition other) {
        return new OrCondition(this, other);
    }

    default Condition not() {
        return new NotCondition(this);
    }
}
