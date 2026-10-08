package dev.supavolt.api.common;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.RowMapper;

/**
 * Turns rows of arbitrary tables into JSON-ready maps. JDBC hands back {@code java.sql} types that
 * Jackson would write as epoch numbers or driver internals; this maps them to what the .NET API
 * returned: ISO date-times, json/jsonb as its text, arrays as lists.
 */
public final class Rows {

    /** For JdbcClient and JdbcTemplate queries over tables we know nothing about. */
    public static final RowMapper<Map<String, Object>> MAPPER = (rs, rowNum) -> read(rs, columns(rs));

    private Rows() {
    }

    public static List<String> columns(ResultSet rs) throws SQLException {
        var meta = rs.getMetaData();
        var names = new ArrayList<String>(meta.getColumnCount());
        for (var i = 1; i <= meta.getColumnCount(); i++) names.add(meta.getColumnLabel(i));
        return names;
    }

    /** Duplicate column names keep the last value, as the .NET API did. */
    public static Map<String, Object> read(ResultSet rs, List<String> columns) throws SQLException {
        var meta = rs.getMetaData();
        var row = new LinkedHashMap<String, Object>(columns.size() * 2);

        for (var i = 1; i <= columns.size(); i++)
            row.put(columns.get(i - 1), value(rs, i, meta.getColumnTypeName(i)));

        return row;
    }

    private static Object value(ResultSet rs, int index, String pgType) throws SQLException {
        if (rs.getObject(index) == null) return null;

        return switch (pgType) {
            case "timestamptz" -> rs.getObject(index, OffsetDateTime.class);
            case "timestamp" -> rs.getObject(index, LocalDateTime.class);
            case "date" -> rs.getObject(index, LocalDate.class);
            case "time" -> rs.getObject(index, LocalTime.class);
            case "json", "jsonb" -> rs.getString(index);
            default -> convert(rs.getObject(index));
        };
    }

    private static Object convert(Object value) throws SQLException {
        if (value instanceof PGobject pg) return pg.getValue();
        if (value instanceof Array array) {
            var items = new ArrayList<Object>();
            for (var item : Arrays.asList((Object[]) array.getArray())) items.add(convert(item));
            return items;
        }
        if (value instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof java.sql.Time time) return time.toLocalTime();
        return value;
    }
}
