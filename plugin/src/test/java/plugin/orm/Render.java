package plugin.orm;

import java.util.ArrayList;
import java.util.List;

import plugin.orm.condition.Condition;

public final class Render {

    private Render() {
    }

    public static String condition(Condition condition, List<Object> parameters) {
        StringBuilder sql = new StringBuilder();
        condition.appendSql(sql, parameters);
        return sql.toString();
    }

    public static String condition(Condition condition) {
        return condition(condition, new ArrayList<>());
    }

    public static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
