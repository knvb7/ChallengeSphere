package com.ironside.challengesphere.api.response;

public record StravaConnectionResponse(
        String provider,
        String athleteId,
        String username,
        String displayName,
        String avatarUrl,
        String scopes,
        String connectedAt,
        String updatedAt,
        String lastSyncedAt,
        StravaActivitySummary snapshot
) {
}
