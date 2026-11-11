package com.aiworkforce.core.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a correlation identifier to every request and writes one access-log line.
 *
 * <ul>
 *   <li>A client-supplied {@code X-Request-Id} is reused only if it matches a strict format,
 *       so it cannot inject content into logs; otherwise a UUID is generated.</li>
 *   <li>The identifier is placed in the MDC ({@value #MDC_KEY}) for every log line of the request
 *       and echoed in the response header.</li>
 *   <li>The access log contains method, path, status and duration only: no query string,
 *       headers (in particular {@code Authorization}) or payloads.</li>
 * </ul>
 *
 * <p>Runs before Spring Security so that rejected requests are correlated too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("com.aiworkforce.core.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        long start = System.nanoTime();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            logAccess(request, response.getStatus(), durationMs);
            MDC.remove(MDC_KEY);
        }
    }

    static String resolveRequestId(String candidate) {
        return candidate != null && VALID_REQUEST_ID.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }

    private static void logAccess(HttpServletRequest request, int status, long durationMs) {
        String path = request.getRequestURI();
        // Health probes are polled constantly: keep them out of the INFO log.
        if (path.startsWith("/actuator/")) {
            ACCESS_LOG.debug("{} {} -> {} ({} ms)", request.getMethod(), path, status, durationMs);
        } else {
            ACCESS_LOG.info("{} {} -> {} ({} ms)", request.getMethod(), path, status, durationMs);
        }
    }
}
