package plugin.orm.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SqlQuery {
    private final String sql;
    private final List<Object> parameters;

    public SqlQuery(String sql, List<Object> parameters) {
        this.sql = sql;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    public String sql() {
        return sql;
    }

    public List<Object> parameters() {
        return parameters;
    }

    @Override
    public String toString() {
        return sql + " " + parameters;
    }
}
