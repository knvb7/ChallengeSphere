package com.ironside.challengesphere.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitStore rateLimitStore;
    private volatile List<IpRange> trustedProxyRanges = List.of();

    public RateLimitingFilter(RateLimitStore rateLimitStore) {
        this.rateLimitStore = rateLimitStore;
    }

    @Value("${app.security.trusted-proxy-cidrs:}")
    void setTrustedProxyCidrs(String trustedProxyCidrs) {
        if (trustedProxyCidrs == null || trustedProxyCidrs.isBlank()) {
            this.trustedProxyRanges = List.of();
            return;
        }

        List<IpRange> parsedRanges = new ArrayList<>();
        for (String cidr : trustedProxyCidrs.split(",")) {
            String trimmed = cidr.trim();
            if (!trimmed.isEmpty()) {
                parsedRanges.add(IpRange.parse(trimmed));
            }
        }
        this.trustedProxyRanges = List.copyOf(parsedRanges);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Limit limit = limitFor(request);
        if (limit != null) {
            RateLimitStore.Decision decision;
            try {
                decision = rateLimitStore.consume(
                        rateLimitKey(limit.name(), clientKey(request)),
                        limit.maxRequests(),
                        limit.window()
                );
            } catch (RuntimeException unavailable) {
                response.setStatus(503);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("Retry-After", "1");
                response.getWriter().write(
                        "{\"errorMessage\":\"Request protection is temporarily unavailable\",\"errorCode\":503}"
                );
                return;
            }

            response.setHeader("X-RateLimit-Limit", Integer.toString(limit.maxRequests()));
            response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
            if (!decision.allowed()) {
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Cache-Control", "no-store");
                response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
                response.getWriter().write("{\"errorMessage\":\"Too many requests\",\"errorCode\":429}");
                return;
            }
        }

        filterChain.doFilter(request, response);
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
        if ("POST".equals(method) && path.equals("/api/v1/auth/forgot-password")) {
            return new Limit("password-reset-request", 5, Duration.ofMinutes(15));
        }
        if ("POST".equals(method) && path.equals("/api/v1/auth/reset-password")) {
            return new Limit("password-reset-attempt", 5, Duration.ofMinutes(15));
        }
        if ("POST".equals(method) && (path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/google")
                || path.equals("/api/v1/auth/refresh"))) {
            return new Limit("auth", 20, Duration.ofMinutes(5));
        }
        if (("POST".equals(method) && path.equals("/api/v1/invites/redeem"))
                || ("GET".equals(method)
                && path.startsWith("/api/v1/invites/")
                && !path.startsWith("/api/v1/invites/challenge/"))) {
            return new Limit("invite-probe", 20, Duration.ofMinutes(5));
        }
        if ("POST".equals(method) && path.equals("/api/v1/invites")) {
            return new Limit("invite-create", 20, Duration.ofMinutes(5));
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
                || path.equals("/api/v1/submissions/presigned-urls")
                || path.equals("/api/v1/submissions/upload-fallback"))) {
            return new Limit("upload-url", 20, Duration.ofMinutes(1));
        }

        return null;
    }

    private String clientKey(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }

        List<String> chain = new ArrayList<>();
        for (String entry : forwardedFor.split(",")) {
            String ip = entry.trim();
            if (!ip.isEmpty()) {
                chain.add(ip);
            }
        }
        chain.add(remoteAddr);

        for (int i = chain.size() - 1; i >= 0; i--) {
            String candidate = chain.get(i);
            if (!isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return remoteAddr;
    }

    private boolean isTrustedProxy(String ipAddress) {
        return trustedProxyRanges.stream().anyMatch(range -> range.contains(ipAddress));
    }

    private String rateLimitKey(String bucket, String clientIdentity) {
        return "rate-limit:v1:" + bucket + ":" + sha256(clientIdentity == null ? "unknown" : clientIdentity);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private record Limit(String name, int maxRequests, Duration window) {
    }

    private record IpRange(BigInteger network, int prefixLength, int totalBits) {

        static IpRange parse(String cidr) {
            String[] parts = cidr.split("/", -1);
            if (parts.length > 2 || parts[0].isBlank()) {
                throw new IllegalStateException("Invalid trusted proxy CIDR: " + cidr);
            }

            byte[] address = toAddressBytes(parts[0]);
            int totalBits = address.length * 8;
            int prefixLength = parts.length == 2 ? parsePrefix(parts[1], totalBits) : totalBits;
            BigInteger network = new BigInteger(1, address).shiftRight(totalBits - prefixLength);
            return new IpRange(network, prefixLength, totalBits);
        }

        boolean contains(String ipAddress) {
            try {
                byte[] address = InetAddress.getByName(ipAddress).getAddress();
                if (address.length * 8 != totalBits) {
                    return false;
                }
                BigInteger candidateNetwork = new BigInteger(1, address).shiftRight(totalBits - prefixLength);
                return network.equals(candidateNetwork);
            } catch (UnknownHostException ex) {
                return false;
            }
        }

        private static byte[] toAddressBytes(String ipAddress) {
            try {
                return InetAddress.getByName(ipAddress).getAddress();
            } catch (UnknownHostException ex) {
                throw new IllegalStateException("Invalid trusted proxy IP address: " + ipAddress, ex);
            }
        }

        private static int parsePrefix(String prefix, int totalBits) {
            try {
                int value = Integer.parseInt(prefix);
                if (value < 0 || value > totalBits) {
                    throw new IllegalStateException("Invalid trusted proxy CIDR prefix: " + prefix);
                }
                return value;
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("Invalid trusted proxy CIDR prefix: " + prefix, ex);
            }
        }
    }
}
