package com.ironside.challengesphere.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChallengeControllerTimezoneTest {

    private ChallengeController controller;

    @BeforeEach
    void setUp() {
        controller = new ChallengeController();
        ReflectionTestUtils.setField(
                controller,
                "clock",
                Clock.fixed(Instant.parse("2026-07-29T20:30:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void resolvesTodayFromTheDeviceIanaTimezone() {
        assertEquals(LocalDate.parse("2026-07-29"), controller.resolveCurrentDate("UTC"));
        assertEquals(LocalDate.parse("2026-07-30"), controller.resolveCurrentDate("Asia/Kolkata"));
    }

    @Test
    void invalidOrMissingTimezoneFallsBackToUtc() {
        assertEquals(LocalDate.parse("2026-07-29"), controller.resolveCurrentDate("not/a-zone"));
        assertEquals(LocalDate.parse("2026-07-29"), controller.resolveCurrentDate(null));
        assertEquals(LocalDate.parse("2026-07-29"), controller.resolveCurrentDate("  "));
    }
}
