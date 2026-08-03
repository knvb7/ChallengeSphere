package com.ironside.challengesphere.security;

import com.ironside.challengesphere.util.UserRole;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class RateLimitingFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void targetedChallengeInvitesUseAuthenticatedPerUserRateLimit() throws Exception {
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.consume(anyString(), eq(10), eq(Duration.ofMinutes(1))))
                .thenReturn(new RateLimitStore.Decision(true, 9, 0));
        RateLimitingFilter filter = new RateLimitingFilter(store);
        FilterChain chain = mock(FilterChain.class);

        UserPrincipal principal = new UserPrincipal(7L, "alex", "", true, UserRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/v1/challenges/51/invites"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(store).consume(anyString(), eq(10), eq(Duration.ofMinutes(1)));
        verify(chain).doFilter(request, response);
        assertEquals("10", response.getHeader("X-RateLimit-Limit"));
        assertEquals("9", response.getHeader("X-RateLimit-Remaining"));
    }

    @Test
    void untrustedClientCannotSpoofForwardedForToRotateRateLimitKey() throws Exception {
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.consume(anyString(), eq(20), eq(Duration.ofMinutes(5))))
                .thenReturn(new RateLimitStore.Decision(true, 19, 0));
        RateLimitingFilter filter = new RateLimitingFilter(store);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/google");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(store).consume(key.capture(), eq(20), eq(Duration.ofMinutes(5)));
        assertEquals(rateLimitKey("auth", "ip:203.0.113.7"), key.getValue());
    }

    @Test
    void trustedProxyUsesRightmostUntrustedForwardedAddress() throws Exception {
        RateLimitStore store = mock(RateLimitStore.class);
        when(store.consume(anyString(), eq(20), eq(Duration.ofMinutes(5))))
                .thenReturn(new RateLimitStore.Decision(true, 19, 0));
        RateLimitingFilter filter = new RateLimitingFilter(store);
        filter.setTrustedProxyCidrs("10.0.0.0/8");
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/google");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.8, 10.0.0.4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(store).consume(key.capture(), eq(20), eq(Duration.ofMinutes(5)));
        assertEquals(rateLimitKey("auth", "ip:203.0.113.8"), key.getValue());
    }

    private String rateLimitKey(String bucket, String identity) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "rate-limit:v1:" + bucket + ":" + java.util.HexFormat.of().formatHex(digest);
    }
}
