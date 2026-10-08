package dev.supavolt.api.infrastructure.mail;

import dev.supavolt.api.config.SupavoltProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MailConfig {

    /** No API key configured means the console sender, so development never sends real mail. */
    @Bean
    EmailSender emailSender(SupavoltProperties properties) {
        var mail = properties.mail();
        return mail.apiKey().isBlank() ? new ConsoleEmailSender() : new ResendEmailSender(mail);
    }
}
