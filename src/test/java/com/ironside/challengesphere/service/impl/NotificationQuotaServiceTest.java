package com.ironside.challengesphere.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationQuotaServiceTest {

    @Test
    void usesExactLocalDateKeyWithNineteenPushLimitAndFortyEightHourTtl() {
        assertEquals(19, NotificationQuotaService.MAX_NOTIFICATIONS_PER_USER_PER_DAY);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        NotificationQuotaService service = new NotificationQuotaService(
                redisTemplate,
                Clock.fixed(Instant.parse("2026-07-27T19:10:00Z"), ZoneOffset.UTC)
        );
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenAnswer(invocation -> {
            return invocation.getArgument(5, String.class);
        });

        Optional<NotificationQuotaService.Reservation> reservation = service.reserve(
                42L,
                "DAILY_UPLOAD_REMINDER:2026-07-27"
        );

        assertTrue(reservation.isPresent());
        assertEquals(42L, reservation.orElseThrow().recipientUserId());
        assertTrue(reservation.orElseThrow().deduplicated());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                keys.capture(),
                anyString(),
                eq("19"),
                eq("172800000"),
                eq(reservation.orElseThrow().member())
        );
        assertEquals("notifications:push-count:42:2026-07-27", keys.getValue().get(0));
        assertTrue(keys.getValue().get(1).startsWith("notification-quota:dedupe:{42}:"));
    }

    @Test
    void allowsPushesOneThroughNineteenAndSkipsTwenty() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        NotificationQuotaService service = new NotificationQuotaService(redisTemplate, Clock.systemUTC());
        AtomicInteger count = new AtomicInteger();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> count.incrementAndGet() <= 19
                        ? invocation.getArgument(5, String.class)
                        : null);

        for (int push = 1; push <= 19; push++) {
            assertTrue(service.reserve(42L, LocalDate.parse("2026-07-31")).isPresent());
        }
        assertFalse(service.reserve(42L, LocalDate.parse("2026-07-31")).isPresent());
    }

    @Test
    void returnsEmptyWhenAtomicQuotaReservationIsRejected() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        NotificationQuotaService service = new NotificationQuotaService(
                redisTemplate,
                Clock.fixed(Instant.parse("2026-07-27T19:10:00Z"), ZoneOffset.UTC)
        );
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(null);

        assertFalse(service.tryReserve(42L));
    }

    @Test
    void releaseRemovesQuotaMemberAndMatchingDedupeReservation() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        NotificationQuotaService service = new NotificationQuotaService(
                redisTemplate,
                Clock.systemUTC()
        );
        NotificationQuotaService.Reservation reservation = new NotificationQuotaService.Reservation(
                42L,
                "notification-quota:user:{42}",
                "notification-quota:dedupe:{42}:daily",
                "member-42",
                true
        );
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenReturn(1L);

        service.release(reservation);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(
                any(RedisScript.class),
                keys.capture(),
                eq(reservation.member()),
                eq("1")
        );
        assertEquals(List.of(reservation.quotaKey(), reservation.dedupeKey()), keys.getValue());
    }

    @Test
    void failsClosedWhenQuotaStoreIsUnavailable() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        NotificationQuotaService service = new NotificationQuotaService(
                redisTemplate,
                Clock.systemUTC()
        );
        when(redisTemplate.execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        )).thenThrow(new IllegalStateException("redis unavailable"));

        assertFalse(service.tryReserve(42L));
    }
}
