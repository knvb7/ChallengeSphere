package com.ironside.challengesphere.api.response;

import java.util.List;

public record HevyWorkoutSummary(
        int workoutCount,
        int exerciseCount,
        int setCount,
        double totalVolumeKg,
        int totalDurationSeconds,
        String syncedAt,
        List<HevyWorkoutSummaryItem> workouts
) {
}
