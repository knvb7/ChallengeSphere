package com.ironside.challengesphere.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

class InvitePreviewSecurityMatcherTest {

    private final RequestMatcher matcher = SecurityConfig.PUBLIC_INVITE_PREVIEW;

    @Test
    void permitsOnlyCanonicalPublicInvitePreviewGets() {
        assertTrue(matches("GET", "/api/v1/invites/Ab12Cd34"));
        assertTrue(matches("GET", "/api/v1/invites/Ab12Cd34Ef56"));

        assertFalse(matches("POST", "/api/v1/invites/Ab12Cd34"));
        assertFalse(matches("GET", "/api/v1/invites/challenge/42"));
        assertFalse(matches("GET", "/api/v1/invites/redeem"));
        assertFalse(matches("GET", "/api/v1/invites/too-short"));
        assertFalse(matches("GET", "/api/v1/invites/Ab12Cd34/extra"));
    }

    private boolean matches(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return matcher.matches(request);
    }
}
