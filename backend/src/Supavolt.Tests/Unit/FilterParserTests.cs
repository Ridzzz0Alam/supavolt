using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Primitives;
using Shouldly;
using Supavolt.Api.Common;
using Supavolt.Api.Features.DataApi;
using Supavolt.Api.Infrastructure.Tenancy;
using Supavolt.Contracts;

namespace Supavolt.Tests.Unit;

public class FilterParserTests
{
    private static ParsedQuery Parse(params (string Key, string Value)[] pairs) =>
        FilterParser.Parse(new QueryCollection(pairs.ToDictionary(p => p.Key, p => new StringValues(p.Value))));

    [Fact]
    public void Eq_produces_one_filter_with_a_string_value()
    {
        var filter = Parse(("name", "eq.Shirt")).Filters.ShouldHaveSingleItem();

        filter.Column.Value.ShouldBe("name");
        filter.Operator.ShouldBe(FilterOperator.Eq);
        filter.Value.ShouldBe("Shirt");
        filter.IsNull.ShouldBeFalse();
    }

    [Fact]
    public void Numbers_are_typed_as_numbers() =>
        Parse(("price", "lt.100")).Filters.Single().Value.ShouldBeOfType<long>().ShouldBe(100);

    [Fact]
    public void Decimals_are_typed_as_decimal() =>
        Parse(("price", "gte.9.99")).Filters.Single().Value.ShouldBe(9.99m);

    [Fact]
    public void Dates_are_typed_as_DateTimeOffset() =>
        Parse(("created_at", "gte.2026-01-01")).Filters.Single().Value.ShouldBeOfType<DateTimeOffset>()
            .Date.ShouldBe(new DateTime(2026, 1, 1));

    [Fact]
    public void Like_values_stay_strings() =>
        Parse(("name", "like.100%")).Filters.Single().Value.ShouldBe("100%");

    [Fact]
    public void Is_null_sets_IsNull_and_has_no_value()
    {
        var filter = Parse(("deleted_at", "is.null")).Filters.Single();
        filter.IsNull.ShouldBeTrue();
        filter.Value.ShouldBeNull();
    }

    [Fact]
    public void Is_not_null_clears_IsNull_and_has_no_value()
    {
        var filter = Parse(("deleted_at", "is.not null")).Filters.Single();
        filter.Operator.ShouldBe(FilterOperator.Is);
        filter.IsNull.ShouldBeFalse();
        filter.Value.ShouldBeNull();
    }

    [Fact]
    public void Is_rejects_anything_but_null_or_not_null() =>
        Should.Throw<AppException>(() => Parse(("x", "is.true"))).Status.ShouldBe(400);

    // The two regressions the parser exists for: the original silently ignored these and
    // returned the whole table.
    [Fact]
    public void Missing_dot_is_a_400() =>
        Should.Throw<AppException>(() => Parse(("price", "lte100"))).Status.ShouldBe(400);

    [Fact]
    public void Unknown_operator_is_a_400() =>
        Should.Throw<AppException>(() => Parse(("price", "bogus.1"))).Status.ShouldBe(400);

    [Theory]
    [InlineData("5000", 1000)]
    [InlineData("0", 1)]
    [InlineData("-3", 1)]
    [InlineData("25", 25)]
    public void Limit_is_clamped(string raw, int expected) =>
        Parse(("limit", raw)).Limit.ShouldBe(expected);

    [Fact]
    public void Limit_defaults_to_100() => Parse().Limit.ShouldBe(100);

    [Fact]
    public void Offset_floors_at_zero() => Parse(("offset", "-10")).Offset.ShouldBe(0);

    [Fact]
    public void Non_integer_limit_is_a_400() =>
        Should.Throw<AppException>(() => Parse(("limit", "ten"))).Status.ShouldBe(400);

    [Fact]
    public void Injection_in_a_column_name_throws() =>
        Should.Throw<InvalidIdentifierException>(() => Parse(("id; DROP TABLE x", "eq.1")));

    [Fact]
    public void Injection_in_select_and_order_throws()
    {
        Should.Throw<InvalidIdentifierException>(() => Parse(("select", "id,name;drop")));
        Should.Throw<InvalidIdentifierException>(() => Parse(("order", "id;drop.desc")));
    }

    [Fact]
    public void Select_and_order_are_parsed()
    {
        var q = Parse(("select", "id, title"), ("order", "id.desc"));
        q.Select.Select(s => s.Value).ShouldBe(["id", "title"]);
        q.Order!.Column.Value.ShouldBe("id");
        q.Order.Descending.ShouldBeTrue();
    }
}
