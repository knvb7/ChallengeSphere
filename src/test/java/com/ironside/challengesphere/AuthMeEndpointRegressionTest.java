package com.ironside.challengesphere;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthMeEndpointRegressionTest {

    @Test
    void authControllerExposesCurrentUserEndpoint() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/ironside/challengesphere/controller/AuthController.java"));

        assertTrue(source.contains("@GetMapping(\"/me\")"),
                "AuthController should expose GET /auth/me for current-user hydration");
        assertTrue(source.contains("SecurityAccess.requireUserId(principal)"),
                "GET /auth/me should resolve the user from the authenticated principal");
        assertTrue(source.contains("userService.getUserDetails(userId, null, null)"),
                "GET /auth/me should fetch the canonical user by authenticated userId");
    }
}
