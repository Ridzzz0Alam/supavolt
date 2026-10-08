package dev.supavolt.api.infrastructure.tenancy;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Postgres identifier that has been validated and is safe to concatenate into SQL text.
 * Values are NEVER concatenated anywhere in this codebase — only identifiers, and only through
 * this type. {@link #toString()} returns the double-quoted form, so {@code schema + "." + table}
 * already produces {@code "schema"."table"}.
 */
public final class SqlIdentifier {

    // matches() must consume the whole input, so "users\n" fails: no trailing-newline loophole.
    private static final Pattern PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]{0,62}");

    private final String value;

    public SqlIdentifier(String value) {
        this(value, "identifier");
    }

    public SqlIdentifier(String value, String label) {
        if (value == null || !PATTERN.matcher(value).matches())
            throw new InvalidIdentifierException(value == null ? "" : value, label);

        this.value = value;
    }

    public static Optional<SqlIdentifier> tryCreate(String value) {
        return value != null && PATTERN.matcher(value).matches()
                ? Optional.of(new SqlIdentifier(value))
                : Optional.empty();
    }

    public String value() {
        return value;
    }

    /** Quoted form, with embedded quotes doubled. Belt and braces: the pattern already rejects them. */
    @Override
    public String toString() {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SqlIdentifier id && id.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
