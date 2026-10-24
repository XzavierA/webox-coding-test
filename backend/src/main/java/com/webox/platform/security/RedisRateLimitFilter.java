package com.webox.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webox.shared.CorrelationIdFilter;
import com.webox.shared.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RedisRateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<List> HIT_SCRIPT = new DefaultRedisScript<>(
            "local count=redis.call('INCR',KEYS[1]); if count==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return {count,redis.call('TTL',KEYS[1])}", List.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Environment environment;

    public RedisRateLimitFilter(StringRedisTemplate redis, ObjectMapper mapper, Environment environment) {
        this.redis = redis; this.mapper = mapper; this.environment = environment;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        Limit limit = limitFor(path);
        if (limit == null || "OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        try {
            String key = "webox:rate-limit:" + limit.name() + ":" + digest(identity(request, limit));
            List<?> result = redis.execute(HIT_SCRIPT, List.of(key), Long.toString(limit.windowSeconds()));
            long hits = Long.parseLong(result.get(0).toString());
            if (hits > limit.maximum()) {
                long retryAfter = Math.max(1L, Long.parseLong(result.get(1).toString()));
                String correlationId = request.getAttribute(CorrelationIdFilter.ATTRIBUTE) instanceof String value
                        ? value : UUID.randomUUID().toString();
                response.setStatus(429);
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                mapper.writeValue(response.getOutputStream(), new Problem("urn:webox:problem:rate_limited", "Too Many Requests", 429,
                        "Too many requests were received. Try again later.", "RATE_LIMITED", correlationId, null, null));
                return;
            }
        } catch (RuntimeException ex) {
            // Keep API availability when Redis is unavailable; limits resume when Redis recovers.
        }
        chain.doFilter(request, response);
    }

    private Limit limitFor(String path) {
        if (!path.startsWith("/api/v1/")) return null;
        if ("/api/v1/auth/login".equals(path)) return configured("login", 10, 600);
        if (path.startsWith("/api/v1/app/orders") || path.startsWith("/api/v1/app/order-submissions")) return configured("order", 10, 60);
        if (path.startsWith("/api/v1/auth/register") || path.startsWith("/api/v1/auth/refresh") || path.startsWith("/api/v1/auth/logout")) return null;
        return configured("general", 120, 60);
    }

    private Limit configured(String name, int defaultMaximum, long defaultWindow) {
        return new Limit(name,
                environment.getProperty("webox.rate-limit." + name + ".limit", Integer.class, defaultMaximum),
                environment.getProperty("webox.rate-limit." + name + ".window-seconds", Long.class, defaultWindow));
    }

    private String identity(HttpServletRequest request, Limit limit) {
        if (!"login".equals(limit.name())) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedActor actor)
                return "user:" + actor.userId();
        }
        return "ip:" + request.getRemoteAddr();
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private record Limit(String name, int maximum, long windowSeconds) { }
}
