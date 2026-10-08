package dev.supavolt.api.features.dataapi;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.infrastructure.tenancy.SqlIdentifier;
import dev.supavolt.contracts.Contracts.FilterOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Parses PostgREST-style query strings: {@code ?price=lt.100&order=id.desc&limit=10&select=id,title}. */
public final class FilterParser {

    public record Filter(SqlIdentifier column, FilterOperator operator, Object value, boolean isNull) {
    }

    public record OrderClause(SqlIdentifier column, boolean descending) {
    }

    public record ParsedQuery(
            List<SqlIdentifier> select, List<Filter> filters, OrderClause order, int limit, int offset) {
    }

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private static final Set<String> RESERVED = Set.of("select", "order", "limit", "offset");
    private static final Pattern GUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private FilterParser() {
    }

    public static String toSqlOperator(FilterOperator op) {
        return switch (op) {
            case EQ -> "=";
            case NEQ -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case LIKE -> "LIKE";
            case ILIKE -> "ILIKE";
            case IS -> "IS";
        };
    }

    /**
     * Unrecognised operators and malformed filters are rejected with a 400. The original skipped
     * them silently, so a typo like ?price=lte100 returned the whole table. Repeated keys are
     * joined with commas.
     */
    public static ParsedQuery parse(Map<String, String[]> query) {
        var select = new ArrayList<SqlIdentifier>();
        var filters = new ArrayList<Filter>();
        OrderClause order = null;
        var limit = DEFAULT_LIMIT;
        var offset = 0;

        for (var entry : query.entrySet()) {
            var key = entry.getKey();
            var value = String.join(",", entry.getValue());
            var reserved = key.toLowerCase(Locale.ROOT);

            if (RESERVED.contains(reserved)) {
                switch (reserved) {
                    case "select" -> {
                        for (var name : value.split(",")) {
                            var trimmed = name.strip();
                            if (!trimmed.isEmpty()) select.add(new SqlIdentifier(trimmed, "select column"));
                        }
                    }
                    case "order" -> {
                        var parts = value.split("\\.", 2);
                        order = new OrderClause(
                                new SqlIdentifier(parts[0], "order column"),
                                parts.length > 1 && parts[1].equalsIgnoreCase("desc"));
                    }
                    case "limit" -> limit = Math.max(1, Math.min(integer(value, "limit"), MAX_LIMIT));
                    default -> offset = Math.max(integer(value, "offset"), 0);
                }
                continue;
            }

            var column = new SqlIdentifier(key, "filter column");
            var separator = value.indexOf('.');

            if (separator <= 0)
                throw AppException.badRequest("Filter on '" + key + "' must be in the form operator.value, e.g. eq.42");

            var operatorText = value.substring(0, separator);
            var rawValue = value.substring(separator + 1);
            var op = operator(operatorText);

            if (op == null)
                throw AppException.badRequest("Unknown operator '" + operatorText + "' on column '" + key + "'");

            if (op == FilterOperator.IS) {
                var isNull = rawValue.equalsIgnoreCase("null");
                var isNotNull = rawValue.replace(" ", "").equalsIgnoreCase("notnull");

                if (!isNull && !isNotNull)
                    throw AppException.badRequest("The 'is' operator accepts only null or not null");

                filters.add(new Filter(column, op, null, isNull));
                continue;
            }

            filters.add(new Filter(column, op, typedValue(op, rawValue), false));
        }

        return new ParsedQuery(select, filters, order, limit, offset);
    }

    private static FilterOperator operator(String text) {
        for (var op : FilterOperator.values())
            if (op.name().equalsIgnoreCase(text)) return op;
        return null;
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw AppException.badRequest(name + " must be an integer");
        }
    }

    /**
     * Values become JDBC parameters, so the driver sends their type. Converting obvious numbers,
     * booleans, uuids and dates here keeps comparisons on typed columns from needing a cast.
     */
    static Object typedValue(FilterOperator op, String raw) {
        if (op == FilterOperator.LIKE || op == FilterOperator.ILIKE) return raw;

        var text = raw.strip();
        if (text.equalsIgnoreCase("true")) return true;
        if (text.equalsIgnoreCase("false")) return false;

        try {
            return Long.parseLong(text.startsWith("+") ? text.substring(1) : text);
        } catch (NumberFormatException ignored) {
            // not an integer
        }

        if (text.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) return new BigDecimal(text);
        if (GUID.matcher(text).matches()) return UUID.fromString(text);

        var date = dateTime(text);
        return date != null ? date : raw;
    }

    /** ISO date-times, with or without an offset; no offset means the server's own, as in .NET. */
    private static OffsetDateTime dateTime(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            // try the next shape
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // try the next shape
        }
        try {
            return LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
