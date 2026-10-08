package dev.supavolt.api.infrastructure.tenancy;

/** A name that failed {@link SqlIdentifier} validation. Always a 400. */
public class InvalidIdentifierException extends RuntimeException {

    public InvalidIdentifierException(String value, String label) {
        super("Invalid " + label + ": '" + value + "'");
    }
}
