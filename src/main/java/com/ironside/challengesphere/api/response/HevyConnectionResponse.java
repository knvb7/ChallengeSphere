package com.ironside.challengesphere.api.response;

public record HevyConnectionResponse(
        String provider,
        String userId,
        String username,
        String displayName,
        String connectedAt,
        String updatedAt,
        String lastSyncedAt,
        HevyWorkoutSummary snapshot
) {
}
