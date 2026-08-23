package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.persistence.dynamodb.ephemeral.EphemeralStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class NotificationQuotaService {

    public static final int MAX_NOTIFICATIONS_PER_USER_PER_DAY = 19;
    private static final Duration QUOTA_TTL = Duration.ofHours(48);

    private static final Logger logger = LogManager.getLogger(NotificationQuotaService.class);

    private final EphemeralStore ephemeralStore;
    private final Clock clock;

    @Autowired
    public NotificationQuotaService(EphemeralStore ephemeralStore) {
        this(ephemeralStore, Clock.systemUTC());
    }

    NotificationQuotaService(EphemeralStore ephemeralStore, Clock clock) {
        this.ephemeralStore = ephemeralStore;
        this.clock = clock;
    }

    public boolean tryReserve(String recipientUserId) {
        return reserve(recipientUserId).isPresent();
    }

    public Optional<Reservation> reserve(String recipientUserId) {
        LocalDate utcDate = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        return reserve(recipientUserId, utcDate, null);
    }

    public Optional<Reservation> reserve(String recipientUserId, String dedupeKey) {
        LocalDate utcDate = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        return reserve(recipientUserId, utcDate, dedupeKey);
    }

    public Optional<Reservation> reserve(String recipientUserId, LocalDate localDate) {
        return reserve(recipientUserId, localDate, null);
    }

    public Optional<Reservation> reserve(String recipientUserId, LocalDate localDate, String dedupeKey) {
        if (recipientUserId == null || recipientUserId.isBlank()) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        LocalDate date = localDate == null
                ? now.atZone(ZoneOffset.UTC).toLocalDate()
                : localDate;
        String member = now.toEpochMilli() + ":" + UUID.randomUUID();
        String quotaKey = "notifications:push-count:" + recipientUserId + ":" + date;
        boolean deduplicated = dedupeKey != null && !dedupeKey.isBlank();
        String storeDedupeKey = deduplicated
                ? "notification-quota:dedupe:{" + recipientUserId + "}:"
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(
                                dedupeKey.getBytes(StandardCharsets.UTF_8))
                : "notification-quota:dedupe:{" + recipientUserId + "}:unused";

        try {
            boolean reserved = ephemeralStore.reserveNotificationQuota(
                    recipientUserId,
                    quotaKey,
                    storeDedupeKey,
                    member,
                    deduplicated,
                    MAX_NOTIFICATIONS_PER_USER_PER_DAY,
                    now.plus(QUOTA_TTL),
                    now
            );
            if (!reserved) {
                return Optional.empty();
            }
            return Optional.of(new Reservation(
                    recipientUserId,
                    quotaKey,
                    storeDedupeKey,
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
            ephemeralStore.releaseNotificationQuota(
                    reservation.recipientUserId(),
                    reservation.quotaKey(),
                    reservation.dedupeKey(),
                    reservation.member(),
                    reservation.deduplicated(),
                    clock.instant()
            );
        } catch (RuntimeException exception) {
            logger.warn("Failed to release notification quota for recipient userId={}: {}",
                    reservation.recipientUserId(), exception.getClass().getSimpleName());
        }
    }

    public record Reservation(
            String recipientUserId,
            String quotaKey,
            String dedupeKey,
            String member,
            boolean deduplicated
    ) {
    }
}
