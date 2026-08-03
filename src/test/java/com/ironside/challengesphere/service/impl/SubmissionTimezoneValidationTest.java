package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubmissionTimezoneValidationTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-27T19:10:00Z"),
            ZoneOffset.UTC
    );

    @Test
    void acceptsCurrentChallengeDayAfterLocalMidnightAheadOfUtc() {
        SubmissionServiceImpl service = new SubmissionServiceImpl(clock);
        Challenge challenge = activeChallengeStartingOn(LocalDate.of(2026, 7, 28));

        assertDoesNotThrow(() ->
                service.ensureSubmissionDayAcceptsUpload(challenge, 1, "Asia/Kolkata"));
    }

    @Test
    void stillRejectsThatDayWhenItIsActuallyInTheFutureForTheClient() {
        SubmissionServiceImpl service = new SubmissionServiceImpl(clock);
        Challenge challenge = activeChallengeStartingOn(LocalDate.of(2026, 7, 28));

        ChallengeSphereException exception = assertThrows(
                ChallengeSphereException.class,
                () -> service.ensureSubmissionDayAcceptsUpload(challenge, 1, "UTC")
        );

        assertEquals("Submissions are not allowed before the challenge starts", exception.getResponseMessage());
    }

    @Test
    void rejectsInvalidClientTimezone() {
        SubmissionServiceImpl service = new SubmissionServiceImpl(clock);
        Challenge challenge = activeChallengeStartingOn(LocalDate.of(2026, 7, 27));

        ChallengeSphereException exception = assertThrows(
                ChallengeSphereException.class,
                () -> service.ensureSubmissionDayAcceptsUpload(challenge, 1, "Not/A_Timezone")
        );

        assertEquals("Invalid timezone", exception.getResponseMessage());
    }

    private Challenge activeChallengeStartingOn(LocalDate startDate) {
        Challenge challenge = new Challenge();
        challenge.setActive(true);
        challenge.setStartDate(startDate);
        challenge.setNumberOfDays(7);
        challenge.setChallengeMode("DAILY");
        return challenge;
    }
}
