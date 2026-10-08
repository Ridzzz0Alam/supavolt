package dev.supavolt.api.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.supavolt.api.infrastructure.tenancy.InvalidIdentifierException;
import dev.supavolt.api.infrastructure.tenancy.SqlIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlIdentifierTest {

    @ParameterizedTest
    @ValueSource(strings = {"users", "_private", "a1", "Mixed_Case_9"})
    void accepts_valid_identifiers(String value) {
        assertThat(new SqlIdentifier(value).value()).isEqualTo(value);
    }

    @Test
    void accepts_a_63_character_name() {
        assertThat(new SqlIdentifier("a".repeat(63)).value()).hasSize(63);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "   ", "1abc", "user name", "users; DROP TABLE x", "\"quoted\"", "a-b", "a.b",
        "users\n",          // a trailing-newline loophole in end-of-input anchors
        "users\r\n", "\nusers", "users\0",
        "usérs",            // non-ASCII letters
        "ｕｓｅｒｓ",        // full-width Latin
        "пользователи",     // Cyrillic
        "users​",      // zero-width space
        "İ",                // Turkish dotted capital I
        "٣abc"              // Arabic-Indic digit
    })
    void rejects_invalid_identifiers(String value) {
        assertThatThrownBy(() -> new SqlIdentifier(value)).isInstanceOf(InvalidIdentifierException.class);
        assertThat(SqlIdentifier.tryCreate(value)).isEmpty();
    }

    @Test
    void rejects_a_64_character_name() {
        assertThatThrownBy(() -> new SqlIdentifier("a".repeat(64))).isInstanceOf(InvalidIdentifierException.class);
    }

    @Test
    void rejects_null() {
        assertThat(SqlIdentifier.tryCreate(null)).isEmpty();
    }

    @Test
    void toString_returns_the_double_quoted_form() {
        assertThat(new SqlIdentifier("todos")).hasToString("\"todos\"");
    }

    @Test
    void concatenation_produces_a_qualified_name() {
        assertThat(new SqlIdentifier("proj_1") + "." + new SqlIdentifier("todos")).isEqualTo("\"proj_1\".\"todos\"");
    }
}
