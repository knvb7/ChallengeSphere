package com.ironside.challengesphere.service.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationQuotaService {

    public static final int MAX_NOTIFICATIONS_PER_USER_PER_DAY = 19;
    private static final Duration QUOTA_TTL = Duration.ofHours(48);

    private static final Logger logger = LogManager.getLogger(NotificationQuotaService.class);
    private static final DefaultRedisScript<String> RESERVE_NOTIFICATION_SCRIPT;
    private static final DefaultRedisScript<Long> RELEASE_NOTIFICATION_SCRIPT;

    static {
        RESERVE_NOTIFICATION_SCRIPT = new DefaultRedisScript<>();
        RESERVE_NOTIFICATION_SCRIPT.setResultType(String.class);
        RESERVE_NOTIFICATION_SCRIPT.setScriptText("""
                if ARGV[1] == '1' and redis.call('EXISTS', KEYS[2]) == 1 then
                    return nil
                end
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                local maximum = tonumber(ARGV[2])
                if current >= maximum then
                    return nil
                end
                local updated = redis.call('INCR', KEYS[1])
                if updated == 1 or redis.call('PTTL', KEYS[1]) < 0 then
                    redis.call('PEXPIRE', KEYS[1], ARGV[3])
                end
                if ARGV[1] == '1' then
                    redis.call('SET', KEYS[2], ARGV[4], 'PX', ARGV[3])
                end
                return ARGV[4]
                """);

        RELEASE_NOTIFICATION_SCRIPT = new DefaultRedisScript<>();
        RELEASE_NOTIFICATION_SCRIPT.setResultType(Long.class);
        RELEASE_NOTIFICATION_SCRIPT.setScriptText("""
                if ARGV[2] == '1' and redis.call('GET', KEYS[2]) ~= ARGV[1] then
                    return 0
                end
                if ARGV[2] == '1' then
                    redis.call('DEL', KEYS[2])
                end
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                if current <= 1 then
                    redis.call('DEL', KEYS[1])
                else
                    redis.call('DECR', KEYS[1])
                end
                return 1
                """);
    }

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    @Autowired
    public NotificationQuotaService(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC());
    }

    NotificationQuotaService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public boolean tryReserve(Long recipientUserId) {
        return reserve(recipientUserId).isPresent();
    }

    public Optional<Reservation> reserve(Long recipientUserId) {
        LocalDate utcDate = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        return reserve(recipientUserId, utcDate, null);
    }

    public Optional<Reservation> reserve(Long recipientUserId, String dedupeKey) {
        LocalDate utcDate = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        return reserve(recipientUserId, utcDate, dedupeKey);
    }

    public Optional<Reservation> reserve(Long recipientUserId, LocalDate localDate) {
        return reserve(recipientUserId, localDate, null);
    }

    public Optional<Reservation> reserve(Long recipientUserId, LocalDate localDate, String dedupeKey) {
        if (recipientUserId == null || recipientUserId <= 0) {
            return Optional.empty();
        }

        LocalDate date = localDate == null
                ? clock.instant().atZone(ZoneOffset.UTC).toLocalDate()
                : localDate;
        String member = clock.instant().toEpochMilli() + ":" + UUID.randomUUID();
        String quotaKey = "notifications:push-count:" + recipientUserId + ":" + date;
        boolean deduplicated = dedupeKey != null && !dedupeKey.isBlank();
        String redisDedupeKey = deduplicated
                ? "notification-quota:dedupe:{" + recipientUserId + "}:"
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(
                                dedupeKey.getBytes(StandardCharsets.UTF_8))
                : "notification-quota:dedupe:{" + recipientUserId + "}:unused";

        try {
            String result = redisTemplate.execute(
                    RESERVE_NOTIFICATION_SCRIPT,
                    List.of(quotaKey, redisDedupeKey),
                    deduplicated ? "1" : "0",
                    Integer.toString(MAX_NOTIFICATIONS_PER_USER_PER_DAY),
                    Long.toString(QUOTA_TTL.toMillis()),
                    member
            );
            if (!member.equals(result)) {
                return Optional.empty();
            }
            return Optional.of(new Reservation(
                    recipientUserId,
                    quotaKey,
                    redisDedupeKey,
                    member,
                    deduplicated
            ));
        } catch (RuntimeException exception) {
            logger.error("Notification quota unavailable for recipient userId={}: {}",
                    recipientUserId, exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void release(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        try {
            redisTemplate.execute(
                    RELEASE_NOTIFICATION_SCRIPT,
                    List.of(reservation.quotaKey(), reservation.dedupeKey()),
                    reservation.member(),
                    reservation.deduplicated() ? "1" : "0"
            );
        } catch (RuntimeException exception) {
            logger.warn("Failed to release notification quota for recipient userId={}: {}",
                    reservation.recipientUserId(), exception.getClass().getSimpleName());
        }
    }

    public record Reservation(
            Long recipientUserId,
            String quotaKey,
            String dedupeKey,
            String member,
            boolean deduplicated
    ) {
    }
}
