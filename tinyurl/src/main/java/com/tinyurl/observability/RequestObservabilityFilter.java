package com.tinyurl.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestObservabilityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestObservabilityFilter.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String CORRELATION_ID_MDC_KEY = "correlation_id";

    private final MeterRegistry meterRegistry;

    public RequestObservabilityFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        long startNanos = System.nanoTime();
        int statusCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

        try {
            filterChain.doFilter(request, response);
            statusCode = response.getStatus();
        } finally {
            try {
                String method = request.getMethod();
                String route = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                if (route == null || route.isBlank()) {
                    route = "UNMAPPED";
                }

                String status = Integer.toString(statusCode);
                String statusClass = (statusCode / 100) + "xx";
                String outcome = statusCode >= 400 ? "error" : "success";

                long durationNanos = System.nanoTime() - startNanos;
                Timer.builder("tinyurl.http.server.request.duration")
                    .tag("method", method)
                    .tag("route", route)
                    .tag("status", status)
                    .register(meterRegistry)
                    .record(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);

                Counter.builder("tinyurl.http.server.requests.total")
                    .tag("method", method)
                    .tag("route", route)
                    .tag("status_class", statusClass)
                    .tag("outcome", outcome)
                    .register(meterRegistry)
                    .increment();

                log.info(
                    "http_request method={} route={} status={} duration_ms={} client_ip={} user_agent={}",
                    method,
                    route,
                    statusCode,
                    durationNanos / 1_000_000,
                    request.getRemoteAddr(),
                    sanitize(request.getHeader("User-Agent"))
                );
            } finally {
                MDC.remove(CORRELATION_ID_MDC_KEY);
            }
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.replaceAll("[\r\n]", " ");
    }
}
