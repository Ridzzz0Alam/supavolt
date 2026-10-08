package dev.supavolt.api.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.features.dataapi.FilterParser;
import dev.supavolt.api.features.dataapi.FilterParser.ParsedQuery;
import dev.supavolt.api.infrastructure.tenancy.InvalidIdentifierException;
import dev.supavolt.api.infrastructure.tenancy.SqlIdentifier;
import dev.supavolt.contracts.Contracts.FilterOperator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FilterParserTest {

    private static ParsedQuery parse(String... pairs) {
        var query = new LinkedHashMap<String, String[]>();
        for (var i = 0; i < pairs.length; i += 2) query.put(pairs[i], new String[] {pairs[i + 1]});
        return FilterParser.parse(query);
    }

    private static int statusOf(Runnable action) {
        try {
            action.run();
        } catch (AppException e) {
            return e.status();
        }
        throw new AssertionError("Expected an AppException");
    }

    @Test
    void eq_produces_one_filter_with_a_string_value() {
        var filters = parse("name", "eq.Shirt").filters();
        assertThat(filters).hasSize(1);

        var filter = filters.get(0);
        assertThat(filter.column().value()).isEqualTo("name");
        assertThat(filter.operator()).isEqualTo(FilterOperator.EQ);
        assertThat(filter.value()).isEqualTo("Shirt");
        assertThat(filter.isNull()).isFalse();
    }

    @Test
    void numbers_are_typed_as_numbers() {
        assertThat(parse("price", "lt.100").filters().get(0).value()).isEqualTo(100L);
    }

    @Test
    void decimals_are_typed_as_decimal() {
        assertThat(parse("price", "gte.9.99").filters().get(0).value()).isEqualTo(new BigDecimal("9.99"));
    }

    @Test
    void dates_are_typed_as_date_times() {
        var value = parse("created_at", "gte.2026-01-01").filters().get(0).value();
        assertThat(value).isInstanceOf(OffsetDateTime.class);
        assertThat(((OffsetDateTime) value).toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void like_values_stay_strings() {
        assertThat(parse("name", "like.100%").filters().get(0).value()).isEqualTo("100%");
    }

    @Test
    void is_null_sets_isNull_and_has_no_value() {
        var filter = parse("deleted_at", "is.null").filters().get(0);
        assertThat(filter.isNull()).isTrue();
        assertThat(filter.value()).isNull();
    }

    @Test
    void is_not_null_clears_isNull_and_has_no_value() {
        var filter = parse("deleted_at", "is.not null").filters().get(0);
        assertThat(filter.operator()).isEqualTo(FilterOperator.IS);
        assertThat(filter.isNull()).isFalse();
        assertThat(filter.value()).isNull();
    }

    @Test
    void is_rejects_anything_but_null_or_not_null() {
        assertThat(statusOf(() -> parse("x", "is.true"))).isEqualTo(400);
    }

    // The two regressions the parser exists for: the original silently ignored these and
    // returned the whole table.
    @Test
    void missing_dot_is_a_400() {
        assertThat(statusOf(() -> parse("price", "lte100"))).isEqualTo(400);
    }

    @Test
    void unknown_operator_is_a_400() {
        assertThat(statusOf(() -> parse("price", "bogus.1"))).isEqualTo(400);
    }

    @ParameterizedTest
    @CsvSource({"5000, 1000", "0, 1", "-3, 1", "25, 25"})
    void limit_is_clamped(String raw, int expected) {
        assertThat(parse("limit", raw).limit()).isEqualTo(expected);
    }

    @Test
    void limit_defaults_to_100() {
        assertThat(parse().limit()).isEqualTo(100);
    }

    @Test
    void offset_floors_at_zero() {
        assertThat(parse("offset", "-10").offset()).isZero();
    }

    @Test
    void non_integer_limit_is_a_400() {
        assertThat(statusOf(() -> parse("limit", "ten"))).isEqualTo(400);
    }

    @Test
    void injection_in_a_column_name_throws() {
        assertThatThrownBy(() -> parse("id; DROP TABLE x", "eq.1")).isInstanceOf(InvalidIdentifierException.class);
    }

    @Test
    void injection_in_select_and_order_throws() {
        assertThatThrownBy(() -> parse("select", "id,name;drop")).isInstanceOf(InvalidIdentifierException.class);
        assertThatThrownBy(() -> parse("order", "id;drop.desc")).isInstanceOf(InvalidIdentifierException.class);
    }

    @Test
    void select_and_order_are_parsed() {
        var q = parse("select", "id, title", "order", "id.desc");
        assertThat(q.select()).extracting(SqlIdentifier::value).containsExactly("id", "title");
        assertThat(q.order().column().value()).isEqualTo("id");
        assertThat(q.order().descending()).isTrue();
    }
}
