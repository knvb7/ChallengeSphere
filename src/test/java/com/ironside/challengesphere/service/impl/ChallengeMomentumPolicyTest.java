package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.model.Challenge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeMomentumPolicyTest {

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private final ChallengeMomentumPolicy policy = new ChallengeMomentumPolicy();

    @ParameterizedTest
    @CsvSource({
            "3,1",
            "7,3",
            "14,7",
            "30,9",
            "90,27"
    })
    void usesExactFinalWindowThresholds(int totalDays, int expectedWindow) {
        assertEquals(expectedWindow, policy.finalWindowDays(totalDays));
    }

    @Test
    void entersFinalStretchWhenFullCompletionRemainsReachable() {
        ChallengeMomentumPolicy.Result result = evaluate(
                30, 21, false, START.plusDays(21)).orElseThrow();

        assertEquals(ChallengeMomentumPolicy.State.FINAL_STRETCH, result.state());
        assertEquals(9, result.remainingRequiredCheckIns());
        assertEquals(9, result.remainingAvailableCheckIns());
    }

    @Test
    void submittedTodayRemovesTodayFromRemainingOpportunities() {
        ChallengeMomentumPolicy.Result result = evaluate(
                30, 22, true, START.plusDays(21)).orElseThrow();

        assertEquals(ChallengeMomentumPolicy.State.FINAL_STRETCH, result.state());
        assertEquals(8, result.remainingRequiredCheckIns());
        assertEquals(8, result.remainingAvailableCheckIns());
        assertTrue(result.submittedToday());
    }

    @Test
    void entersFinishStrongWhenCompletionIsUnreachableAndFinalOpportunitiesBegin() {
        ChallengeMomentumPolicy.Result result = evaluate(
                30, 10, false, START.plusDays(21)).orElseThrow();

        assertEquals(ChallengeMomentumPolicy.State.FINISH_STRONG, result.state());
        assertEquals(20, result.remainingRequiredCheckIns());
        assertEquals(9, result.remainingAvailableCheckIns());
    }

    @Test
    void staysNeutralWhileMoreThanTheFinalWindowRemains() {
        ChallengeMomentumPolicy.Result result = evaluate(
                30, 10, false, START.plusDays(20)).orElseThrow();

        assertEquals(ChallengeMomentumPolicy.State.NONE, result.state());
        assertEquals(10, result.remainingAvailableCheckIns());
    }

    @Test
    void lateJoiningDoesNotReduceTheOriginalCompletionTarget() {
        ChallengeMomentumPolicy.Result result = evaluate(
                14, 2, false, START.plusDays(7)).orElseThrow();

        assertEquals(14, result.totalDays());
        assertEquals(12, result.remainingRequiredCheckIns());
        assertEquals(7, result.remainingAvailableCheckIns());
        assertEquals(ChallengeMomentumPolicy.State.FINISH_STRONG, result.state());
    }

    @Test
    void excludesOneOffInactiveCompletedLobbyFutureAndExpiredChallenges() {
        assertTrue(policy.evaluate(input(Challenge.MODE_ONE_OFF, true, false,
                false, false, 7, 0, false, START)).isEmpty());
        assertTrue(policy.evaluate(input(Challenge.MODE_DAILY, false, false,
                false, false, 7, 0, false, START)).isEmpty());
        assertTrue(policy.evaluate(input(Challenge.MODE_DAILY, true, true,
                false, false, 7, 2, false, START.plusDays(2))).isEmpty());
        assertTrue(policy.evaluate(input(Challenge.MODE_DAILY, true, false,
                true, true, 7, 0, false, START)).isEmpty());
        assertTrue(policy.evaluate(input(Challenge.MODE_DAILY, true, false,
                false, false, 7, 0, false, START.minusDays(1))).isEmpty());
        assertTrue(policy.evaluate(input(Challenge.MODE_DAILY, true, false,
                false, false, 7, 3, false, START.plusDays(7))).isEmpty());
    }

    private Optional<ChallengeMomentumPolicy.Result> evaluate(
            int totalDays,
            int completedDays,
            boolean submittedToday,
            LocalDate currentDate
    ) {
        return policy.evaluate(input(
                Challenge.MODE_DAILY,
                true,
                false,
                false,
                false,
                totalDays,
                completedDays,
                submittedToday,
                currentDate
        ));
    }

    private ChallengeMomentumPolicy.Input input(
            String challengeMode,
            boolean challengeActive,
            boolean participantCompleted,
            boolean privateChallenge,
            boolean privateLobby,
            int totalDays,
            int completedDays,
            boolean submittedToday,
            LocalDate currentDate
    ) {
        return new ChallengeMomentumPolicy.Input(
                challengeMode,
                challengeActive,
                participantCompleted,
                privateChallenge,
                privateLobby,
                START,
                totalDays,
                completedDays,
                submittedToday,
                currentDate
        );
    }
}
