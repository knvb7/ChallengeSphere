package com.ironside.challengesphere.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceSecretValidationTest {

    @Test
    void productionRejectsDefaultAndLowDiversitySecrets() {
        assertThrows(IllegalStateException.class, () -> service(
                "challengesphere-local-dev-secret-change-me-please-256-bits-long", "prod"));
        assertThrows(IllegalStateException.class, () -> service(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "prod"));
        assertThrows(IllegalStateException.class, () -> service(
                "default-jwt-secret-that-is-long-enough-but-unsafe", "prod"));
    }

    @Test
    void productionAcceptsStrongRandomLookingSecret() {
        assertDoesNotThrow(() -> service(
                "QS9eNXh3V1pCNmRrZEZxMW1YUjFWSnViSEdzM1VQdWE=", "prod"));
    }

    private JwtService service(String secret, String profile) {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", profile);
        environment.setActiveProfiles(profile);
        JwtService service = new JwtService(environment, null);
        ReflectionTestUtils.setField(service, "jwtSecret", secret);
        service.validateSigningSecret();
        return service;
    }
}
