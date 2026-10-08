package dev.supavolt.api.common;

import org.springframework.http.HttpStatus;

/** A failure the caller caused. The handler turns it into ProblemDetails with this status and message. */
public class AppException extends RuntimeException {

    private final int status;

    public AppException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static AppException notFound(String what) {
        return new AppException(HttpStatus.NOT_FOUND.value(), what + " not found");
    }

    public static AppException conflict(String message) {
        return new AppException(HttpStatus.CONFLICT.value(), message);
    }

    public static AppException badRequest(String message) {
        return new AppException(HttpStatus.BAD_REQUEST.value(), message);
    }

    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN.value(), message);
    }

    public static AppException unauthorized() {
        return unauthorized("Invalid credentials");
    }

    public static AppException unauthorized(String message) {
        return new AppException(HttpStatus.UNAUTHORIZED.value(), message);
    }
}
