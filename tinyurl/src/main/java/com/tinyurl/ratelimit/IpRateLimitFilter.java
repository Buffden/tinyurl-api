package com.tinyurl.ratelimit;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Application-level per-IP rate limit on URL creation.
 *
 * Complements the nginx rate limit zone (40r/m) by enforcing a tighter hourly
 * cap per IP at the application layer. This catches slow distributed attacks
 * that stay under the nginx per-minute threshold but accumulate over time.
 *
 * Limit: 20 URL creations per IP per hour (rolling window via token bucket).
 * Storage: Caffeine in-process cache, entries expire after 2 hours of inactivity.
 *
 * IP resolution: reads X-Real-IP set by nginx from Cloudflare's CF-Connecting-IP,
 * falling back to the socket address (the nginx container IP in prod).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IpRateLimitFilter extends OncePerRequestFilter {

    private static final int HOURLY_LIMIT = 20;

    private static final String RATE_LIMIT_RESPONSE =
        "{\"status\":429,\"error\":\"Too Many Requests\","
        + "\"message\":\"Hourly URL creation limit exceeded. Please try again later.\"}";

    private final LoadingCache<String, Bucket> buckets = Caffeine.newBuilder()
        .expireAfterAccess(2, TimeUnit.HOURS)
        .build(ip -> newBucket());

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
            && "/api/urls".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String ip = resolveClientIp(request);
        Bucket bucket = buckets.get(ip);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(RATE_LIMIT_RESPONSE);
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    private static Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
            .capacity(HOURLY_LIMIT)
            .refillGreedy(HOURLY_LIMIT, Duration.ofHours(1))
            .build();
        return Bucket.builder().addLimit(limit).build();
    }
}
