package plugin.orm;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import plugin.orm.table.Column;

public final class Row {
    private final List<String> columnNames;
    private final Map<String, Object> values;

    private Row(List<String> columnNames, Map<String, Object> values) {
        this.columnNames = List.copyOf(columnNames);
        this.values = values;
    }

    public static Row from(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> labels = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            labels.add(metaData.getColumnName(i));
            tables.add(metaData.getTableName(i));
        }

        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String label : labels) {
            frequency.merge(label, 1, Integer::sum);
        }

        List<String> names = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < columnCount; i++) {
            String label = labels.get(i);
            String key = frequency.get(label) > 1 && !tables.get(i).isEmpty()
                    ? tables.get(i) + "." + label
                    : label;
            names.add(key);
            values.put(key, rs.getObject(i + 1));
        }

        return new Row(names, values);
    }

    public List<String> columnNames() {
        return columnNames;
    }

    public int size() {
        return values.size();
    }

    public Object getObject(String columnName) {
        if (values.containsKey(columnName)) {
            return values.get(columnName);
        }
        String match = null;
        for (String key : values.keySet()) {
            if (key.endsWith("." + columnName)) {
                if (match != null) {
                    throw new OrmException(
                            "Ambiguous column name '" + columnName + "' in row: " + values.keySet());
                }
                match = key;
            }
        }
        if (match != null) {
            return values.get(match);
        }
        throw new OrmException("No column named '" + columnName + "' in row: " + values.keySet());
    }

    public <T> T get(String columnName, Class<T> type) {
        return SqlTypeConverter.convert(getObject(columnName), type);
    }

    public <T> T get(Column<T> column) {
        if (values.containsKey(column.qualifiedName())) {
            return get(column.qualifiedName(), column.type());
        }
        return get(column.name(), column.type());
    }

    public String getString(String columnName) {
        return get(columnName, String.class);
    }

    public long getLong(String columnName) {
        return get(columnName, Long.class);
    }

    public int getInt(String columnName) {
        return get(columnName, Integer.class);
    }

    public short getShort(String columnName) {
        return get(columnName, Short.class);
    }

    public byte getByte(String columnName) {
        return get(columnName, Byte.class);
    }

    public boolean getBoolean(String columnName) {
        return get(columnName, Boolean.class);
    }

    public float getFloat(String columnName) {
        return get(columnName, Float.class);
    }

    public double getDouble(String columnName) {
        return get(columnName, Double.class);
    }

    public byte[] getBytes(String columnName) {
        return get(columnName, byte[].class);
    }

    public UUID getUuid(String columnName) {
        return get(columnName, UUID.class);
    }

    public Instant getInstant(String columnName) {
        return get(columnName, Instant.class);
    }

    public <E extends Enum<E>> E getEnum(String columnName, Class<E> enumType) {
        return get(columnName, enumType);
    }
}
