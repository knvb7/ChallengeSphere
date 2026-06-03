package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.response.HevyConnectionResponse;
import com.ironside.challengesphere.api.response.HevyWorkoutSummary;
import com.ironside.challengesphere.model.UserIntegration;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HevyIntegrationServiceImplTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void summarizesRecentWorkoutsForStrengthChallengeProof() throws Exception {
        JsonNode workouts = objectMapper.readTree("""
                [
                  {
                    "id": "workout-1",
                    "title": "Push Day",
                    "start_time": "2026-05-30T08:00:00Z",
                    "end_time": "2026-05-30T09:10:00Z",
                    "exercises": [
                      {
                        "title": "Bench Press",
                        "sets": [
                          { "weight_kg": 100, "reps": 5 },
                          { "weight_kg": 95, "reps": 6 }
                        ]
                      },
                      {
                        "title": "Shoulder Press",
                        "sets": [
                          { "weight_kg": 50, "reps": 8 }
                        ]
                      }
                    ]
                  },
                  {
                    "id": "workout-2",
                    "name": "Pull Day",
                    "start_time": "2026-05-29T08:00:00Z",
                    "duration_seconds": 3600,
                    "exercises": [
                      {
                        "name": "Pull Up",
                        "sets": [
                          { "reps": 10 },
                          { "reps": 8 }
                        ]
                      }
                    ]
                  }
                ]
                """);

        HevyWorkoutSummary summary = HevyIntegrationServiceImpl.toWorkoutSummary(
                workouts,
                Instant.parse("2026-05-31T01:00:00Z")
        );

        assertEquals(2, summary.workoutCount());
        assertEquals(3, summary.exerciseCount());
        assertEquals(5, summary.setCount());
        assertEquals(1470.0, summary.totalVolumeKg());
        assertEquals(7800, summary.totalDurationSeconds());
        assertEquals("2026-05-31T01:00:00Z", summary.syncedAt());
        assertEquals("Push Day", summary.workouts().get(0).title());
        assertEquals("Bench Press, Shoulder Press", summary.workouts().get(0).exercisePreview());
        assertEquals(3, summary.workouts().get(0).setCount());
    }

    @Test
    void mapsStoredIntegrationWithoutReturningApiKey() {
        UserIntegration integration = new UserIntegration();
        integration.setProvider("HEVY");
        integration.setExternalUserId("hevy-user-123");
        integration.setExternalUsername("mihir");
        integration.setDisplayName("Mihir Dalal");
        integration.setAccessToken("hevy-secret-api-key");
        integration.setConnectedAt(Timestamp.from(Instant.parse("2026-05-31T01:00:00Z")));
        integration.setUpdatedAt(Timestamp.from(Instant.parse("2026-05-31T01:02:00Z")));

        HevyConnectionResponse response = HevyIntegrationServiceImpl.toConnectionResponse(integration, null);

        assertEquals("HEVY", response.provider());
        assertEquals("hevy-user-123", response.userId());
        assertEquals("mihir", response.username());
        assertEquals("Mihir Dalal", response.displayName());
        assertNull(response.snapshot());
        assertFalse(response.toString().contains("hevy-secret-api-key"));
    }
}
