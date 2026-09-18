using Shouldly;
using Supavolt.Api.Infrastructure.Tenancy;

namespace Supavolt.Tests.Unit;

public class SqlIdentifierTests
{
    [Theory]
    [InlineData("users")]
    [InlineData("_private")]
    [InlineData("a1")]
    [InlineData("Mixed_Case_9")]
    public void Accepts_valid_identifiers(string value) =>
        new SqlIdentifier(value).Value.ShouldBe(value);

    [Fact]
    public void Accepts_a_63_character_name() =>
        new SqlIdentifier(new string('a', 63)).Value.Length.ShouldBe(63);

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("1abc")]
    [InlineData("user name")]
    [InlineData("users; DROP TABLE x")]
    [InlineData("\"quoted\"")]
    [InlineData("a-b")]
    [InlineData("a.b")]
    [InlineData("users\n")]          // .NET's '$' matches before a trailing newline
    [InlineData("users\r\n")]
    [InlineData("\nusers")]
    [InlineData("users\0")]
    [InlineData("usérs")]            // non-ASCII letters
    [InlineData("ｕｓｅｒｓ")]        // full-width Latin
    [InlineData("пользователи")]     // Cyrillic
    [InlineData("users​")]      // zero-width space
    [InlineData("İ")]                // Turkish dotted capital I
    [InlineData("٣abc")]             // Arabic-Indic digit
    public void Rejects_invalid_identifiers(string value)
    {
        Should.Throw<InvalidIdentifierException>(() => new SqlIdentifier(value));
        SqlIdentifier.TryCreate(value, out _).ShouldBeFalse();
    }

    [Fact]
    public void Rejects_a_64_character_name() =>
        Should.Throw<InvalidIdentifierException>(() => new SqlIdentifier(new string('a', 64)));

    [Fact]
    public void Rejects_null() =>
        SqlIdentifier.TryCreate(null, out _).ShouldBeFalse();

    [Fact]
    public void ToString_returns_the_double_quoted_form() =>
        new SqlIdentifier("todos").ToString().ShouldBe("\"todos\"");

    [Fact]
    public void Interpolation_produces_a_qualified_name() =>
        $"{new SqlIdentifier("proj_1")}.{new SqlIdentifier("todos")}".ShouldBe("\"proj_1\".\"todos\"");
}
