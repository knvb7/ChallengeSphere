package com.ironside.challengesphere.api.response;

public record StravaActivitySummaryItem(
        String id,
        String name,
        String sportType,
        double distanceKm,
        int movingSeconds,
        String startedAt,
        String externalId
) {
}
