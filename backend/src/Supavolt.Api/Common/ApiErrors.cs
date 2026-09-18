using Microsoft.AspNetCore.Diagnostics;
using Npgsql;
using Supavolt.Api.Infrastructure.Tenancy;

namespace Supavolt.Api.Common;

public sealed class AppException(int status, string message) : Exception(message)
{
    public int Status { get; } = status;

    public static AppException NotFound(string what) => new(StatusCodes.Status404NotFound, $"{what} not found");
    public static AppException Conflict(string message) => new(StatusCodes.Status409Conflict, message);
    public static AppException BadRequest(string message) => new(StatusCodes.Status400BadRequest, message);
    public static AppException Forbidden(string message) => new(StatusCodes.Status403Forbidden, message);
    public static AppException Unauthorized(string message = "Invalid credentials") =>
        new(StatusCodes.Status401Unauthorized, message);
}

/// <summary>
/// Turns domain and Postgres exceptions into ProblemDetails. The original returned raw Postgres
/// messages with a 500, which leaks schema details to anyone holding an anon key.
/// </summary>
public sealed class AppExceptionHandler(IProblemDetailsService problems, ILogger<AppExceptionHandler> log)
    : IExceptionHandler
{
    public async ValueTask<bool> TryHandleAsync(HttpContext ctx, Exception ex, CancellationToken ct)
    {
        var (status, title, detail) = ex switch
        {
            AppException app => (app.Status, app.Message, (string?)null),

            InvalidIdentifierException bad => (StatusCodes.Status400BadRequest, bad.Message, null),

            PostgresException pg => pg.SqlState switch
            {
                PostgresErrorCodes.UniqueViolation      => (StatusCodes.Status409Conflict, "Duplicate value", SafeDetail(pg)),
                PostgresErrorCodes.ForeignKeyViolation  => (StatusCodes.Status422UnprocessableEntity, "Referenced row does not exist", SafeDetail(pg)),
                PostgresErrorCodes.NotNullViolation     => (StatusCodes.Status422UnprocessableEntity, "Missing required value", SafeDetail(pg)),
                PostgresErrorCodes.UndefinedColumn      => (StatusCodes.Status400BadRequest, "Unknown column", SafeDetail(pg)),
                PostgresErrorCodes.UndefinedTable       => (StatusCodes.Status404NotFound, "Table not found", null),
                PostgresErrorCodes.InsufficientPrivilege => (StatusCodes.Status403Forbidden, "Not permitted", null),
                PostgresErrorCodes.QueryCanceled        => (StatusCodes.Status408RequestTimeout, "Query cancelled: statement timeout reached", null),
                PostgresErrorCodes.SyntaxError          => (StatusCodes.Status400BadRequest, "SQL syntax error", pg.MessageText),
                _                                       => (StatusCodes.Status400BadRequest, "Database error", null)
            },

            OperationCanceledException => (499, "Request cancelled", null),

            _ => (StatusCodes.Status500InternalServerError, "Unexpected error", null)
        };

        if (status >= 500) log.LogError(ex, "Unhandled exception on {Path}", ctx.Request.Path);
        else log.LogDebug(ex, "Handled {Status} on {Path}", status, ctx.Request.Path);

        ctx.Response.StatusCode = status;

        return await problems.TryWriteAsync(new ProblemDetailsContext
        {
            HttpContext = ctx,
            ProblemDetails = { Status = status, Title = title, Detail = detail }
        });
    }

    /// <summary>Constraint names are safe to surface; the full Postgres message often is not.</summary>
    private static string? SafeDetail(PostgresException pg) =>
        pg.ConstraintName is { Length: > 0 } c ? $"Constraint: {c}" : null;
}

