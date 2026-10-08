package dev.supavolt.api.common;

import java.util.Collection;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Converts JSON request values into JDBC parameters. Values always travel as parameters. */
public final class SqlParams {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SqlParams() {
    }

    /**
     * Numbers become long or BigDecimal, strings, booleans and nulls stay as they are, and objects
     * and arrays go in as JSON text, still as a parameter, for a json or jsonb column.
     */
    public static Object value(Object value) {
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) return JSON.writeValueAsString(value);
        if (value instanceof Double || value instanceof Float) return new java.math.BigDecimal(value.toString());
        return value;
    }

    public static Object value(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isString()) return node.asString();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isIntegralNumber() && node.canConvertToLong()) return node.longValue();
        if (node.isNumber()) return node.decimalValue();
        return node.toString();
    }
}
