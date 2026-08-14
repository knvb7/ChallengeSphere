package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GoogleAuthRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthServiceImplGoogleCredentialsTest {

    private static final List<String> ALLOWED_AUDIENCES = List.of("trusted-client-id");

    @Test
    void fallsBackToAccessTokenWhenSuppliedIdTokenIsInvalid() throws ChallengeSphereException {
        StubAuthService service = new StubAuthService();
        service.idTokenFailure = authFailure("Invalid Google ID token");
        service.accessTokenProfile = profile("access-user");

        AuthServiceImpl.GoogleProfile result = verify(service, request("invalid-id-token", "valid-access-token"));

        assertSame(service.accessTokenProfile, result);
        assertEquals(1, service.idTokenVerifications);
        assertEquals(1, service.accessTokenVerifications);
    }

    @Test
    void doesNotUseAccessTokenWhenIdTokenIsValid() throws ChallengeSphereException {
        StubAuthService service = new StubAuthService();
        service.idTokenProfile = profile("id-user");
        service.accessTokenProfile = profile("access-user");

        AuthServiceImpl.GoogleProfile result = verify(service, request("valid-id-token", "valid-access-token"));

        assertSame(service.idTokenProfile, result);
        assertEquals(1, service.idTokenVerifications);
        assertEquals(0, service.accessTokenVerifications);
    }

    @Test
    void doesNotBypassAnUnverifiedGoogleEmail() {
        StubAuthService service = new StubAuthService();
        ChallengeSphereException failure = authFailure("Google account email is not verified");
        service.idTokenFailure = failure;
        service.accessTokenProfile = profile("access-user");

        ChallengeSphereException result = assertThrows(
                ChallengeSphereException.class,
                () -> verify(service, request("unverified-id-token", "valid-access-token"))
        );

        assertSame(failure, result);
        assertEquals(1, service.idTokenVerifications);
        assertEquals(0, service.accessTokenVerifications);
    }

    private static AuthServiceImpl.GoogleProfile verify(
            AuthServiceImpl service,
            GoogleAuthRequest request
    ) throws ChallengeSphereException {
        try {
            return ReflectionTestUtils.invokeMethod(
                    service,
                    "verifyGoogleCredentials",
                    request,
                    ALLOWED_AUDIENCES
            );
        } catch (RuntimeException error) {
            if (error.getCause() instanceof ChallengeSphereException authFailure) {
                throw authFailure;
            }
            throw error;
        }
    }

    private static GoogleAuthRequest request(String idToken, String accessToken) {
        GoogleAuthRequest request = new GoogleAuthRequest();
        request.setIdToken(idToken);
        request.setAccessToken(accessToken);
        return request;
    }

    private static AuthServiceImpl.GoogleProfile profile(String googleId) {
        return new AuthServiceImpl.GoogleProfile(
                googleId,
                googleId + "@example.com",
                "Google User",
                null
        );
    }

    private static ChallengeSphereException authFailure(String message) {
        return new ChallengeSphereException(message, HttpStatus.UNAUTHORIZED, null);
    }

    private static final class StubAuthService extends AuthServiceImpl {
        private GoogleProfile idTokenProfile;
        private GoogleProfile accessTokenProfile;
        private ChallengeSphereException idTokenFailure;
        private int idTokenVerifications;
        private int accessTokenVerifications;

        private StubAuthService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        GoogleProfile verifyGoogleIdToken(String idTokenString, List<String> allowedAudiences)
                throws ChallengeSphereException {
            idTokenVerifications++;
            if (idTokenFailure != null) {
                throw idTokenFailure;
            }
            return idTokenProfile;
        }

        @Override
        GoogleProfile verifyGoogleAccessToken(String accessToken, List<String> allowedAudiences) {
            accessTokenVerifications++;
            return accessTokenProfile;
        }
    }
}
