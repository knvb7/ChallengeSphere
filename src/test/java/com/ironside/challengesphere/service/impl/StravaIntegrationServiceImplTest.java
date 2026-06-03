package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.StravaActivitySummary;
import com.ironside.challengesphere.api.response.StravaConnectionResponse;
import com.ironside.challengesphere.integration.strava.StravaAthlete;
import com.ironside.challengesphere.integration.strava.StravaDetailedActivity;
import com.ironside.challengesphere.integration.strava.StravaTokenResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StravaIntegrationServiceImplTest {

    @Test
    void generatedOAuthStateVerifiesOnlyForSameUserAndBeforeExpiry() {
        StravaOAuthStateSigner signer = new StravaOAuthStateSigner("test-secret-key-with-more-than-32-bytes");
        String state = signer.sign(42L, Instant.parse("2026-05-31T00:00:00Z"));

        assertTrue(signer.verify(state, 42L, Instant.parse("2026-05-31T00:09:59Z")));
        assertFalse(signer.verify(state, 41L, Instant.parse("2026-05-31T00:09:59Z")));
        assertFalse(signer.verify(state, 42L, Instant.parse("2026-05-31T00:10:01Z")));
    }

    @Test
    void mapsTokenResponseToSafeConnectionWithoutReturningTokens() {
        StravaAthlete athlete = new StravaAthlete();
        athlete.setId(123456L);
        athlete.setUsername("runner");
        athlete.setFirstname("Mira");
        athlete.setLastname("Patel");
        athlete.setProfile("https://images.example/runner.jpg");

        StravaTokenResponse token = new StravaTokenResponse();
        token.setAccessToken("access-token");
        token.setRefreshToken("refresh-token");
        token.setExpiresAt(1_779_888_000L);
        token.setAthlete(athlete);

        StravaConnectionResponse response = StravaIntegrationServiceImpl.toConnectionResponse(
                token,
                "read,activity:read",
                Instant.parse("2026-05-31T01:00:00Z"),
                null
        );

        assertEquals("STRAVA", response.provider());
        assertEquals("123456", response.athleteId());
        assertEquals("runner", response.username());
        assertEquals("Mira Patel", response.displayName());
        assertEquals("https://images.example/runner.jpg", response.avatarUrl());
        assertEquals("read,activity:read", response.scopes());
        assertNull(response.snapshot());
        assertFalse(response.toString().contains("access-token"));
        assertFalse(response.toString().contains("refresh-token"));
    }

    @Test
    void summarizesRecentActivitiesForChallengeProof() {
        StravaDetailedActivity run = new StravaDetailedActivity();
        run.setId(101L);
        run.setName("Morning Run");
        run.setSportType("Run");
        run.setDistance(5000.0);
        run.setMovingTime(1500);
        run.setStartDate("2026-05-30T03:30:00Z");
        run.setExternalId("garmin-101");

        StravaDetailedActivity ride = new StravaDetailedActivity();
        ride.setId(102L);
        ride.setName("Evening Ride");
        ride.setType("Ride");
        ride.setDistance(20000.0);
        ride.setMovingTime(3600);
        ride.setStartDate("2026-05-30T13:30:00Z");

        StravaActivitySummary summary = StravaIntegrationServiceImpl.toActivitySummary(
                List.of(run, ride),
                Instant.parse("2026-05-31T01:00:00Z")
        );

        assertEquals(2, summary.activityCount());
        assertEquals(25.0, summary.totalDistanceKm());
        assertEquals(5100, summary.totalMovingSeconds());
        assertEquals("2026-05-31T01:00:00Z", summary.syncedAt());
        assertEquals("Run", summary.activities().get(0).sportType());
        assertEquals(5.0, summary.activities().get(0).distanceKm());
    }
}
