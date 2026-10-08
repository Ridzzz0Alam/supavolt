package dev.supavolt.api.infrastructure.mail;

public interface EmailSender {

    void send(String to, String subject, String html);
}
