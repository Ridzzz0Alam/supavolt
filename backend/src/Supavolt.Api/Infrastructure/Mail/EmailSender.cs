using System.Net.Http.Headers;
using System.Net.Http.Json;
using Microsoft.Extensions.Options;
using Supavolt.Api.Infrastructure.Configuration;

namespace Supavolt.Api.Infrastructure.Mail;

public interface IEmailSender
{
    Task SendAsync(string to, string subject, string html, CancellationToken ct = default);
}

/// <summary>Development default: no key configured means mail goes to the log, not the internet.</summary>
public sealed class ConsoleEmailSender(ILogger<ConsoleEmailSender> log) : IEmailSender
{
    public Task SendAsync(string to, string subject, string html, CancellationToken ct = default)
    {
        log.LogInformation("MAIL → {To}\nSubject: {Subject}\n{Html}", to, subject, html);
        return Task.CompletedTask;
    }
}

/// <summary>
/// Resend has no official .NET SDK, so this calls its REST endpoint directly. Swapping in
/// SendGrid, Postmark or MailKit means replacing this one class.
/// </summary>
public sealed class HttpEmailSender(
    HttpClient http,
    IOptions<MailOptions> options,
    ILogger<HttpEmailSender> log) : IEmailSender
{
    public async Task SendAsync(string to, string subject, string html, CancellationToken ct = default)
    {
        var o = options.Value;
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", o.ApiKey);

        var response = await http.PostAsJsonAsync(o.ApiUrl, new
        {
            from = $"{o.FromName} <{o.FromAddress}>",
            to = new[] { to },
            subject,
            html
        }, ct);

        if (!response.IsSuccessStatusCode)
        {
            var body = await response.Content.ReadAsStringAsync(ct);
            log.LogError("Mail send failed with {Status}: {Body}", (int)response.StatusCode, body);
        }
    }
}
