package com.ironside.challengesphere.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitingFilterTest {

    @Test
    void limitsBackendUploadFallbackUsingUploadRequestQuota() throws Exception {
        RateLimitingFilter filter = new RateLimitingFilter();
        AtomicInteger acceptedRequests = new AtomicInteger();
        FilterChain chain = (request, response) -> acceptedRequests.incrementAndGet();

        for (int index = 0; index < 20; index++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(uploadFallbackRequest(), response, chain);
            assertEquals(200, response.getStatus());
        }

        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        filter.doFilter(uploadFallbackRequest(), rejectedResponse, chain);

        assertEquals(429, rejectedResponse.getStatus());
        assertEquals(20, acceptedRequests.get());
    }

    private MockHttpServletRequest uploadFallbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/submissions/upload-fallback");
        request.setRemoteAddr("127.0.0.50");
        return request;
    }
}
