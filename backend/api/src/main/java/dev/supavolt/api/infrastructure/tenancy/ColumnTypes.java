package dev.supavolt.api.infrastructure.tenancy;

import dev.supavolt.contracts.Contracts.ColumnType;
import java.math.BigDecimal;
import java.util.List;

public final class ColumnTypes {

    /**
     * Allow-listed function defaults. Anything else becomes a quoted literal, and a literal
     * containing a parenthesis is rejected rather than guessed at.
     */
    private static final List<String> ALLOWED_FUNCTIONS = List.of("now()", "gen_random_uuid()", "current_timestamp");

    private ColumnTypes() {
    }

    /**
     * The caller sends a ColumnType enum, so an unknown type fails deserialisation with a 400
     * instead of reaching SQL.
     */
    public static String toSql(ColumnType type) {
        return switch (type) {
            case TEXT -> "TEXT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case BOOLEAN -> "BOOLEAN";
            case TIMESTAMP -> "TIMESTAMPTZ";
            case UUID -> "UUID";
            case JSONB -> "JSONB";
            case NUMERIC -> "NUMERIC";
        };
    }

    public static ColumnType fromPg(String pgType) {
        return switch (pgType) {
            case "text", "character varying", "char", "character" -> ColumnType.TEXT;
            case "integer", "smallint" -> ColumnType.INTEGER;
            case "bigint" -> ColumnType.BIGINT;
            case "boolean" -> ColumnType.BOOLEAN;
            case "timestamp with time zone", "timestamp without time zone" -> ColumnType.TIMESTAMP;
            case "uuid" -> ColumnType.UUID;
            case "jsonb", "json" -> ColumnType.JSONB;
            case "numeric", "double precision", "real" -> ColumnType.NUMERIC;
            default -> ColumnType.TEXT;
        };
    }

    public static String formatDefault(String raw, ColumnType type) {
        var trimmed = raw.trim();

        for (var fn : ALLOWED_FUNCTIONS)
            if (trimmed.equalsIgnoreCase(fn)) return fn;

        if (trimmed.contains("(") || trimmed.contains(")"))
            throw new InvalidIdentifierException(trimmed, "column default");

        var quoted = "'" + trimmed.replace("'", "''") + "'";

        return switch (type) {
            case BOOLEAN -> trimmed.equalsIgnoreCase("true") ? "TRUE"
                    : trimmed.equalsIgnoreCase("false") ? "FALSE" : quoted;
            case INTEGER, BIGINT -> asLong(trimmed) != null ? asLong(trimmed).toString() : quoted;
            case NUMERIC -> asDecimal(trimmed) != null ? asDecimal(trimmed).toPlainString() : quoted;
            case JSONB -> quoted + "::jsonb";
            default -> quoted;
        };
    }

    private static Long asLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal asDecimal(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
