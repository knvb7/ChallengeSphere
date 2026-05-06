package com.ironside.challengesphere.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Limit limit = limitFor(request);
        if (limit != null) {
            String key = limit.name() + ":" + clientKey(request);
            if (!allow(key, limit.maxRequests(), limit.window())) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"errorMessage\":\"Too many requests\",\"errorCode\":429}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean allow(String key, int maxRequests, Duration duration) {
        Instant now = Instant.now();
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || !existing.expiresAt().isAfter(now)) {
                return new Window(1, now.plus(duration));
            }
            return new Window(existing.count() + 1, existing.expiresAt());
        });
        return window.count() <= maxRequests;
    }

    private Limit limitFor(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String method = request.getMethod();
        String path = request.getRequestURI();

        if (path.startsWith("/api/v1/auth/otp/")) {
            return new Limit("otp", 5, Duration.ofMinutes(10));
        }
        if ("POST".equals(method) && (path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/google")
                || path.equals("/api/v1/auth/refresh"))) {
            return new Limit("auth", 20, Duration.ofMinutes(5));
        }
        if ("POST".equals(method) && (path.equals("/api/v1/users/follow")
                || path.equals("/api/v1/users/unfollow"))) {
            return new Limit("follow", 60, Duration.ofMinutes(1));
        }
        if (path.startsWith("/api/v1/comments") && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            return new Limit("comment", 40, Duration.ofMinutes(1));
        }
        if (path.startsWith("/api/v1/glories") && "POST".equals(method)) {
            return new Limit("glory", 120, Duration.ofMinutes(1));
        }
        if ("POST".equals(method) && (path.equals("/api/v1/challenges/join")
                || path.equals("/api/v1/challenges/leave"))) {
            return new Limit("challenge-membership", 30, Duration.ofMinutes(1));
        }
        if ("POST".equals(method) && (path.equals("/api/v1/challenges/presigned-urls")
                || path.equals("/api/v1/submissions/presigned-urls"))) {
            return new Limit("upload-url", 20, Duration.ofMinutes(1));
        }

        return null;
    }

    private String clientKey(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private record Limit(String name, int maxRequests, Duration window) {
    }

    private record Window(int count, Instant expiresAt) {
    }
}
