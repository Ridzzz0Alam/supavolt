package dev.supavolt.api.infrastructure.mail;

import dev.supavolt.api.config.SupavoltProperties;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls Resend's REST endpoint directly, so the URL stays configurable. Swapping in SendGrid,
 * Postmark or SMTP means replacing this one class.
 */
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final RestClient http;
    private final SupavoltProperties.Mail options;

    public ResendEmailSender(SupavoltProperties.Mail options) {
        this.options = options;
        this.http = RestClient.builder()
                .defaultHeader("Authorization", "Bearer " + options.apiKey())
                .build();
    }

    @Override
    public void send(String to, String subject, String html) {
        try {
            http.post()
                    .uri(options.apiUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", options.fromName() + " <" + options.fromAddress() + ">",
                            "to", List.of(to),
                            "subject", subject,
                            "html", html))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("Mail send failed with {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }
}
