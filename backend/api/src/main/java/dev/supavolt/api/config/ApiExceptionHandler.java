package dev.supavolt.api.config;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.infrastructure.tenancy.InvalidIdentifierException;
import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns domain and Postgres exceptions into RFC 9457 ProblemDetails, which lib/api.ts reads as
 * {@code title}/{@code detail}. Raw Postgres messages are not returned: they leak schema details to
 * anyone holding an anon key. Spring MVC's own errors (unreadable body, failed validation, unknown
 * route) are handled by the base class.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AppException.class)
    ResponseEntity<ProblemDetail> app(AppException ex) {
        return problem(ex.status(), ex.getMessage(), null);
    }

    @ExceptionHandler(InvalidIdentifierException.class)
    ResponseEntity<ProblemDetail> identifier(InvalidIdentifierException ex) {
        return problem(400, ex.getMessage(), null);
    }

    /** Method security refusing an org policy. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> denied(AccessDeniedException ex) {
        return problem(403, "Forbidden", null);
    }

    /** A malformed id in the path is an unknown resource, as the .NET route constraints made it. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> mismatch(MethodArgumentTypeMismatchException ex) {
        var isPath = ex.getParameter().hasParameterAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
        return isPath ? problem(404, "Not Found", null) : problem(400, "Invalid value for '" + ex.getName() + "'", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        var pg = postgres(ex);
        if (pg != null) return database(pg);

        log.error("Unhandled exception", ex);
        return problem(500, "Unexpected error", null);
    }

    private ResponseEntity<ProblemDetail> database(PSQLException pg) {
        var state = pg.getSQLState() == null ? "" : pg.getSQLState();
        var server = pg.getServerErrorMessage();

        return switch (state) {
            case "23505" -> problem(409, "Duplicate value", constraint(pg));
            case "23503" -> problem(422, "Referenced row does not exist", constraint(pg));
            case "23502" -> problem(422, "Missing required value", constraint(pg));
            case "42703" -> problem(400, "Unknown column", constraint(pg));
            case "42P01" -> problem(404, "Table not found", null);
            case "42501" -> problem(403, "Not permitted", null);
            case "57014" -> problem(408, "Query cancelled: statement timeout reached", null);
            case "42601" -> problem(400, "SQL syntax error", server == null ? null : server.getMessage());
            default -> {
                log.debug("Handled Postgres error {}", state, pg);
                yield problem(400, "Database error", null);
            }
        };
    }

    /** Constraint names are safe to surface; the full Postgres message often is not. */
    private static String constraint(PSQLException pg) {
        var server = pg.getServerErrorMessage();
        return server != null && server.getConstraint() != null && !server.getConstraint().isEmpty()
                ? "Constraint: " + server.getConstraint()
                : null;
    }

    /** JPA and JdbcClient wrap the driver's exception; find it anywhere in the cause chain. */
    static PSQLException postgres(Throwable ex) {
        for (var cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException pg) return pg;
            if (cause instanceof SQLException sql && sql.getNextException() instanceof PSQLException next) return next;
        }
        return null;
    }

    static ResponseEntity<ProblemDetail> problem(int status, String title, String detail) {
        var body = ProblemDetail.forStatus(status);
        body.setTitle(title);
        body.setDetail(detail);
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(body);
    }
}
