package dev.supavolt.api.infrastructure.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Development default: no API key configured means mail goes to the log, not the internet. */
public class ConsoleEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEmailSender.class);

    @Override
    public void send(String to, String subject, String html) {
        log.info("MAIL → {}\nSubject: {}\n{}", to, subject, html);
    }
}
