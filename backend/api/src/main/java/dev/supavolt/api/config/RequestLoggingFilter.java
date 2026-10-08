package dev.supavolt.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per request, in the shape Serilog's request logging used. Outermost, so the status it
 * records is the one the client actually got.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("dev.supavolt.api.requests");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("HTTP {} {} responded {} in {} ms",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    String.format("%.1f", (System.nanoTime() - start) / 1_000_000.0));
        }
    }

    /** WebSocket upgrades stay open for minutes; logging them on close is just noise. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }
}
