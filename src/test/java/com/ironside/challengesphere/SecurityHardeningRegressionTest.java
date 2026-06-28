package com.ironside.challengesphere;

import com.ironside.challengesphere.security.JwtService;
import com.ironside.challengesphere.security.RateLimitingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHardeningRegressionTest {

    @Test
    void cacheAdminEndpointsRequireAdminRole() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/ironside/challengesphere/config/SecurityConfig.java"));

        assertTrue(source.contains("\"/api/v1/admin/cache/**\""),
                "SecurityConfig must explicitly match cache admin routes");
        assertTrue(source.contains(".hasRole(\"ADMIN\")"),
                "Cache admin routes must require ROLE_ADMIN, not merely any authenticated user");
    }

    @Test
    void productionSwaggerIsDisabledAndNotPublicByDefault() throws Exception {
        String securityConfig = Files.readString(Path.of("src/main/java/com/ironside/challengesphere/config/SecurityConfig.java"));
        String prodProperties = Files.readString(Path.of("src/main/resources/application-prod.properties"));

        assertTrue(securityConfig.contains("app.security.public-docs-enabled"),
                "Swagger/OpenAPI permitAll must be controlled by an explicit opt-in property");
        assertTrue(prodProperties.contains("springdoc.api-docs.enabled=${SPRINGDOC_API_DOCS_ENABLED:false}"),
                "Production API docs must be disabled unless explicitly enabled");
        assertTrue(prodProperties.contains("springdoc.swagger-ui.enabled=${SPRINGDOC_SWAGGER_UI_ENABLED:false}"),
                "Production Swagger UI must be disabled unless explicitly enabled");
        assertTrue(prodProperties.contains("app.security.public-docs-enabled=${PUBLIC_DOCS_ENABLED:false}"),
                "Production Swagger/OpenAPI public access must default to false");
    }

    @Test
    void productionJwtSecretHasNoKnownFallback() throws Exception {
        String prodProperties = Files.readString(Path.of("src/main/resources/application-prod.properties"));

        assertTrue(prodProperties.contains("jwt.secret=${JWT_SECRET}"),
                "Production must require JWT_SECRET instead of falling back to a known local secret");
        assertFalse(prodProperties.contains("jwt.secret=${JWT_SECRET:challengesphere-local-dev-secret-change-me-please-256-bits-long}"),
                "Production JWT secret must not include the local fallback");
    }

    @Test
    void productionProfileDoesNotExposeDevFacingSettings() throws Exception {
        String prodProperties = Files.readString(Path.of("src/main/resources/application-prod.properties"));

        assertTrue(prodProperties.contains("app.cors.allowed-origins=${APP_CORS_ALLOWED_ORIGINS:https://challengesphere.com}"),
                "Production CORS must default to the real frontend, not localhost origins");
        assertTrue(prodProperties.contains("app.frontend.base-url=${APP_FRONTEND_BASE_URL:https://challengesphere.com}"),
                "Production email/frontend links must default to the real frontend");
        assertTrue(prodProperties.contains("app.invite.base-url=${APP_INVITE_BASE_URL:https://challengesphere.com/invite}"),
                "Production invite links must default to the real frontend invite URL");
        assertTrue(prodProperties.contains("spring.jpa.hibernate.show-sql=false"),
                "Production SQL logging must be disabled");
        assertTrue(prodProperties.contains("springdoc.show-actuator=${SPRINGDOC_SHOW_ACTUATOR:false}"),
                "Production OpenAPI must not expose actuator by default");
        assertFalse(prodProperties.contains("http://localhost"),
                "Production profile must not contain localhost defaults");
    }

    @Test
    void productionPemFileIsNotStoredBesideTheProject() {
        assertFalse(Files.exists(Path.of("../challengesphere-backend-prod-keys.pem").normalize()),
                "Production PEM files must not sit beside the project where they can be accidentally shared");
    }

    @Test
    void jwtServiceRejectsShortSecretsInsteadOfPaddingThem() {
        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtSecret", "too-short");

        assertThrows(IllegalStateException.class,
                () -> jwtService.generateAccessToken(1L, "alice"),
                "Short JWT secrets must fail closed instead of being silently padded");
    }

    @Test
    void rateLimitClientKeyIgnoresSpoofedForwardedForWhenProxyIsNotTrusted() {
        RateLimitingFilter filter = new RateLimitingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.77");

        String key = ReflectionTestUtils.invokeMethod(filter, "clientKey", request);

        assertEquals("203.0.113.10", key,
                "X-Forwarded-For must not override the socket address unless the remote peer is a trusted proxy");
    }
}
