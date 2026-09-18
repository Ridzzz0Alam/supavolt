using System.Text.RegularExpressions;

namespace Supavolt.Api.Infrastructure.Tenancy;

public sealed class InvalidIdentifierException(string value, string label)
    : Exception($"Invalid {label}: '{value}'");

/// <summary>
/// A Postgres identifier that has been validated and is safe to interpolate into SQL text.
/// Values are NEVER interpolated anywhere in this codebase — only identifiers, and only through
/// this type. ToString() returns the double-quoted form, so string interpolation of an
/// SqlIdentifier already produces "schema"."table".
/// </summary>
public readonly struct SqlIdentifier : IEquatable<SqlIdentifier>
{
    // \z, not $: in .NET, $ also matches just before a trailing newline, so "users\n" would pass.
    private static readonly Regex Pattern =
        new(@"^[a-zA-Z_][a-zA-Z0-9_]{0,62}\z", RegexOptions.Compiled | RegexOptions.CultureInvariant);

    public string Value { get; }

    public SqlIdentifier(string value, string label = "identifier")
    {
        if (string.IsNullOrWhiteSpace(value) || !Pattern.IsMatch(value))
            throw new InvalidIdentifierException(value ?? "", label);

        Value = value;
    }

    public static bool TryCreate(string? value, out SqlIdentifier identifier)
    {
        if (value is not null && Pattern.IsMatch(value))
        {
            identifier = new SqlIdentifier(value);
            return true;
        }

        identifier = default;
        return false;
    }

    /// <summary>Quoted form, with embedded quotes doubled. Belt and braces: the regex already rejects them.</summary>
    public override string ToString() => $"\"{Value.Replace("\"", "\"\"")}\"";

    public bool Equals(SqlIdentifier other) => string.Equals(Value, other.Value, StringComparison.Ordinal);
    public override bool Equals(object? obj) => obj is SqlIdentifier other && Equals(other);
    public override int GetHashCode() => Value?.GetHashCode(StringComparison.Ordinal) ?? 0;
}
