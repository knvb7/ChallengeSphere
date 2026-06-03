package com.ironside.challengesphere.api.response;

public record HevyWorkoutSummaryItem(
        String id,
        String title,
        String startedAt,
        int durationSeconds,
        int exerciseCount,
        int setCount,
        double volumeKg,
        String exercisePreview
) {
}
