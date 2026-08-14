package com.ironside.challengesphere.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Calculates completion streaks with one protected rest day in any seven-day period.
 * Rest days do not add to the streak length. A second rest day inside the rolling
 * window ends the streak, and the next completed day starts a new one.
 */
public final class StreakCalculator {

    private static final int STREAK_WINDOW_DAYS = 7;

    private StreakCalculator() {
    }

    public static int currentStreak(Collection<Integer> completedDays) {
        return calculate(completedDays, null).current();
    }

    public static int currentStreak(Collection<Integer> completedDays, int throughDay) {
        return calculate(completedDays, Math.max(0, throughDay)).current();
    }

    public static int longestStreak(Collection<Integer> completedDays) {
        return calculate(completedDays, null).longest();
    }

    private static StreakResult calculate(Collection<Integer> completedDays, Integer throughDay) {
        if (completedDays == null || completedDays.isEmpty()) {
            return new StreakResult(0, 0);
        }

        Set<Integer> uniqueDays = new HashSet<>();
        for (Integer day : completedDays) {
            if (day != null && day > 0 && (throughDay == null || day <= throughDay)) {
                uniqueDays.add(day);
            }
        }
        if (uniqueDays.isEmpty()) {
            return new StreakResult(0, 0);
        }

        int firstDay = uniqueDays.stream().min(Integer::compareTo).orElseThrow();
        int lastDay = throughDay != null
                ? throughDay
                : uniqueDays.stream().max(Integer::compareTo).orElseThrow();
        int running = 0;
        int longest = 0;
        Integer protectedRestDay = null;

        for (int day = firstDay; day <= lastDay; day++) {
            if (protectedRestDay != null && day - protectedRestDay >= STREAK_WINDOW_DAYS) {
                protectedRestDay = null;
            }

            if (uniqueDays.contains(day)) {
                running++;
                longest = Math.max(longest, running);
                continue;
            }

            if (running == 0) {
                continue;
            }
            if (protectedRestDay == null) {
                protectedRestDay = day;
            } else {
                running = 0;
                protectedRestDay = null;
            }
        }

        return new StreakResult(running, longest);
    }

    private record StreakResult(int current, int longest) {
    }
}
