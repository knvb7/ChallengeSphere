package com.ironside.challengesphere.api.response;

import java.util.List;

public record StravaActivitySummary(
        int activityCount,
        double totalDistanceKm,
        int totalMovingSeconds,
        String syncedAt,
        List<StravaActivitySummaryItem> activities
) {
}
