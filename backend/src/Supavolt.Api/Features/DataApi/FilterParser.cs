using System.Globalization;
using Supavolt.Api.Common;
using Supavolt.Api.Infrastructure.Tenancy;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.DataApi;

public sealed record Filter(SqlIdentifier Column, FilterOperator Operator, object? Value, bool IsNull);

public sealed record OrderClause(SqlIdentifier Column, bool Descending);

public sealed record ParsedQuery(
    IReadOnlyList<SqlIdentifier> Select,
    IReadOnlyList<Filter> Filters,
    OrderClause? Order,
    int Limit,
    int Offset);

public static class FilterParser
{
    private const int DefaultLimit = 100;
    private const int MaxLimit = 1000;

    private static readonly HashSet<string> Reserved =
        new(StringComparer.OrdinalIgnoreCase) { "select", "order", "limit", "offset" };

    private static readonly Dictionary<string, FilterOperator> Operators = new(StringComparer.OrdinalIgnoreCase)
    {
        ["eq"] = FilterOperator.Eq,
        ["neq"] = FilterOperator.Neq,
        ["gt"] = FilterOperator.Gt,
        ["gte"] = FilterOperator.Gte,
        ["lt"] = FilterOperator.Lt,
        ["lte"] = FilterOperator.Lte,
        ["like"] = FilterOperator.Like,
        ["ilike"] = FilterOperator.Ilike,
        ["is"] = FilterOperator.Is
    };

    public static string ToSqlOperator(this FilterOperator op) => op switch
    {
        FilterOperator.Eq => "=",
        FilterOperator.Neq => "<>",
        FilterOperator.Gt => ">",
        FilterOperator.Gte => ">=",
        FilterOperator.Lt => "<",
        FilterOperator.Lte => "<=",
        FilterOperator.Like => "LIKE",
        FilterOperator.Ilike => "ILIKE",
        FilterOperator.Is => "IS",
        _ => throw new ArgumentOutOfRangeException(nameof(op), op, null)
    };

    /// <summary>
    /// Unrecognised operators and malformed filters are rejected with a 400. The original skipped
    /// them silently, so a typo like ?price=lte100 returned the whole table.
    /// </summary>
    public static ParsedQuery Parse(IQueryCollection query)
    {
        var select = new List<SqlIdentifier>();
        var filters = new List<Filter>();
        OrderClause? order = null;
        var limit = DefaultLimit;
        var offset = 0;

        foreach (var (key, values) in query)
        {
            var value = values.ToString();

            if (Reserved.Contains(key))
            {
                switch (key.ToLowerInvariant())
                {
                    case "select":
                        foreach (var name in value.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
                            select.Add(new SqlIdentifier(name, "select column"));
                        break;

                    case "order":
                    {
                        var parts = value.Split('.', 2);
                        order = new OrderClause(
                            new SqlIdentifier(parts[0], "order column"),
                            parts.Length > 1 && parts[1].Equals("desc", StringComparison.OrdinalIgnoreCase));
                        break;
                    }

                    case "limit":
                        if (!int.TryParse(value, out limit))
                            throw AppException.BadRequest("limit must be an integer");
                        limit = Math.Clamp(limit, 1, MaxLimit);
                        break;

                    case "offset":
                        if (!int.TryParse(value, out offset))
                            throw AppException.BadRequest("offset must be an integer");
                        offset = Math.Max(offset, 0);
                        break;
                }

                continue;
            }

            var column = new SqlIdentifier(key, "filter column");
            var separator = value.IndexOf('.');

            if (separator <= 0)
                throw AppException.BadRequest($"Filter on '{key}' must be in the form operator.value, e.g. eq.42");

            var operatorText = value[..separator];
            var rawValue = value[(separator + 1)..];

            if (!Operators.TryGetValue(operatorText, out var op))
                throw AppException.BadRequest($"Unknown operator '{operatorText}' on column '{key}'");

            if (op == FilterOperator.Is)
            {
                var isNull = rawValue.Equals("null", StringComparison.OrdinalIgnoreCase);
                var isNotNull = rawValue.Replace(" ", "").Equals("notnull", StringComparison.OrdinalIgnoreCase);

                if (!isNull && !isNotNull)
                    throw AppException.BadRequest("The 'is' operator accepts only null or not null");

                filters.Add(new Filter(column, op, null, isNull));
                continue;
            }

            filters.Add(new Filter(column, op, TypedValue(op, rawValue), false));
        }

        return new ParsedQuery(select, filters, order, limit, offset);
    }

    /// <summary>
    /// Values become Dapper parameters, so Npgsql infers the Postgres type. Converting obvious
    /// numbers and booleans here keeps comparisons on typed columns from needing a cast.
    /// </summary>
    private static object? TypedValue(FilterOperator op, string raw)
    {
        if (op is FilterOperator.Like or FilterOperator.Ilike) return raw;

        if (bool.TryParse(raw, out var b)) return b;
        if (long.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out var l)) return l;
        if (decimal.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out var d)) return d;
        if (Guid.TryParse(raw, out var g)) return g;
        if (DateTimeOffset.TryParse(raw, CultureInfo.InvariantCulture, DateTimeStyles.None, out var dt)) return dt;

        return raw;
    }
}
