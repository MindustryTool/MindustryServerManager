package plugin.orm;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SqlTypeConverter {

    private SqlTypeConverter() {
    }

    public static void bindAll(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            bind(statement, i + 1, parameters.get(i));
        }
    }

    public static void bind(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NULL);
        } else if (value instanceof Boolean bool) {
            statement.setInt(index, bool ? 1 : 0);
        } else if (value instanceof UUID uuid) {
            statement.setString(index, uuid.toString());
        } else if (value instanceof Instant instant) {
            statement.setString(index, instant.toString());
        } else if (value instanceof Enum<?> e) {
            statement.setString(index, e.name());
        } else {
            statement.setObject(index, value);
        }
    }

    public static <T> T convert(Object raw, Class<T> type) {
        if (raw == null) {
            return null;
        }
        try {
            return doConvert(raw, type);
        } catch (RuntimeException e) {
            if (e instanceof OrmException) {
                throw e;
            }
            throw new OrmException(
                    "Cannot convert value of type " + raw.getClass().getName() + " to " + type.getName(), e);
        }
    }

    public static String columnTypeFor(Class<?> type) {
        if (type == String.class || type == UUID.class || type == Instant.class || type.isEnum()) {
            return "TEXT";
        }
        if (type == Integer.class || type == Long.class || type == Short.class || type == Byte.class
                || type == Boolean.class) {
            return "INTEGER";
        }
        if (type == Float.class || type == Double.class) {
            return "REAL";
        }
        if (type == byte[].class) {
            return "BLOB";
        }
        throw new OrmException("No SQLite column type for " + type.getName());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T> T doConvert(Object raw, Class<T> type) {
        if (type == Object.class || type.isInstance(raw)) {
            return (T) raw;
        }
        if (type == String.class) {
            return (T) (raw instanceof String ? raw : String.valueOf(raw));
        }
        if (type == Boolean.class) {
            return (T) Boolean.valueOf(asBoolean(raw));
        }
        if (type == Integer.class) {
            return (T) Integer.valueOf(asInt(raw));
        }
        if (type == Long.class) {
            return (T) Long.valueOf(asLong(raw));
        }
        if (type == Short.class) {
            return (T) Short.valueOf((short) asInt(raw));
        }
        if (type == Byte.class) {
            return (T) Byte.valueOf((byte) asInt(raw));
        }
        if (type == Float.class) {
            return (T) Float.valueOf((float) asDouble(raw));
        }
        if (type == Double.class) {
            return (T) Double.valueOf(asDouble(raw));
        }
        if (type == byte[].class) {
            return (T) raw;
        }
        if (type == UUID.class) {
            return (T) UUID.fromString(String.valueOf(raw));
        }
        if (type == Instant.class) {
            return (T) Instant.parse(String.valueOf(raw));
        }
        if (type.isEnum()) {
            return (T) Enum.valueOf((Class<? extends Enum>) type, String.valueOf(raw));
        }
        throw new OrmException("Cannot convert value of type " + raw.getClass().getName() + " to " + type.getName());
    }

    private static boolean asBoolean(Object raw) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static int asInt(Object raw) {
        if (raw instanceof Integer i) {
            return i;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(raw));
    }

    private static long asLong(Object raw) {
        if (raw instanceof Long l) {
            return l;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(raw));
    }

    private static double asDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(raw));
    }
}
