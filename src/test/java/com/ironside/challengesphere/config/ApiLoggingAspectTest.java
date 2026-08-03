package com.ironside.challengesphere.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiLoggingAspectTest {

    private final ApiLoggingAspect aspect = new ApiLoggingAspect(new ObjectMapper());

    @Test
    void usesControlledApplicationExceptionReason() {
        ChallengeSphereException exception = new ChallengeSphereException(
                "Invalid or expired refresh token",
                HttpStatus.UNAUTHORIZED,
                null
        );

        assertThat(ApiLoggingAspect.safeExceptionDetail(exception))
                .isEqualTo("Invalid or expired refresh token");
    }

    @Test
    void sanitizesMultilineExceptionDetails() {
        IllegalStateException exception = new IllegalStateException("first line\r\nsecond\tline");

        assertThat(ApiLoggingAspect.safeExceptionDetail(exception))
                .isEqualTo("first line  second line");
    }

    @Test
    void limitsExceptionDetailLength() {
        IllegalStateException exception = new IllegalStateException("x".repeat(600));

        assertThat(ApiLoggingAspect.safeExceptionDetail(exception))
                .hasSize(503)
                .endsWith("...");
    }

    @Test
    void unwrapsStoredApplicationExceptionCauseForServerFailureLogging() {
        IllegalStateException cause = new IllegalStateException("database unavailable");
        ChallengeSphereException exception = new ChallengeSphereException(
                "Unable to complete request",
                HttpStatus.INTERNAL_SERVER_ERROR,
                null,
                cause
        );

        assertThat(ApiLoggingAspect.loggingThrowable(exception)).isSameAs(cause);
    }

    @Test
    void logsRequestBodyWhileRedactingSensitiveFieldsRecursively() throws Exception {
        Method method = PayloadController.class.getDeclaredMethod(
                "create",
                String.class,
                Map.class
        );
        Map<String, Object> body = Map.of(
                "challengeId", 42,
                "reason", "Repeated abuse",
                "refreshToken", "refresh-secret-value",
                "profile", Map.of(
                        "email", "private@example.com",
                        "displayName", "Athlete"
                )
        );

        String payload = aspect.safeRequestPayload(method, new Object[]{"Bearer access-secret", body});

        assertThat(payload)
                .contains("\"challengeId\":42")
                .contains("\"reason\":\"Repeated abuse\"")
                .contains("\"displayName\":\"Athlete\"")
                .contains("\"refreshToken\":\"[REDACTED]\"")
                .contains("\"email\":\"[REDACTED]\"")
                .doesNotContain("refresh-secret-value")
                .doesNotContain("private@example.com")
                .doesNotContain("Bearer access-secret");
    }

    @Test
    void reportsNoneWhenEndpointHasNoRequestBody() throws Exception {
        Method method = PayloadController.class.getDeclaredMethod("read", Long.class);

        assertThat(aspect.safeRequestPayload(method, new Object[]{42L})).isEqualTo("none");
    }

    @Test
    void limitsSerializedPayloadLength() throws Exception {
        Method method = PayloadController.class.getDeclaredMethod(
                "create",
                String.class,
                Map.class
        );
        Map<String, Object> body = Map.of("reason", "x".repeat(2_100));

        assertThat(aspect.safeRequestPayload(method, new Object[]{null, body}))
                .hasSize(2_003)
                .endsWith("...");
    }

    @Test
    void payloadSerializationFailureDoesNotEscapeTheLogger() throws Exception {
        Method method = PayloadController.class.getDeclaredMethod(
                "broken",
                UnserializablePayload.class
        );

        assertThat(aspect.safeRequestPayload(method, new Object[]{new UnserializablePayload()}))
                .startsWith("[UNAVAILABLE:");
    }

    private static class PayloadController {
        @SuppressWarnings("unused")
        void create(
                @RequestHeader("Authorization") String authorization,
                @RequestBody Map<String, Object> body
        ) {
        }

        @SuppressWarnings("unused")
        void read(Long id) {
        }

        @SuppressWarnings("unused")
        void broken(@RequestBody UnserializablePayload body) {
        }
    }

    private static class UnserializablePayload {
        public String getValue() {
            throw new IllegalStateException("cannot serialize");
        }
    }
}
