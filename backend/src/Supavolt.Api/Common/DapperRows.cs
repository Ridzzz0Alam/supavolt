namespace Supavolt.Api.Common;

/// <summary>
/// Dapper's dynamic rows are <c>DapperRow</c>, which implements <see cref="IDictionary{TKey,TValue}"/>
/// but is not a <see cref="Dictionary{TKey,TValue}"/>, so a direct cast throws at runtime.
/// </summary>
public static class DapperRows
{
    public static Dictionary<string, object?> ToRow(object row) =>
        ((IDictionary<string, object>)row).ToDictionary(kv => kv.Key, kv => (object?)kv.Value);
}
