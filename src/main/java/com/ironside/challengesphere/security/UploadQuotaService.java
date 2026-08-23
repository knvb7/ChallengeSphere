package com.ironside.challengesphere.security;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.persistence.dynamodb.ephemeral.EphemeralStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Atomically reserves each user's daily direct-upload allowance before S3 URLs
 * are minted. This bounds orphan-object and storage-cost abuse independently of
 * IP-based request throttling.
 */
@Service
public class UploadQuotaService {

    private static final Logger logger = LogManager.getLogger(UploadQuotaService.class);

    private final EphemeralStore ephemeralStore;
    private final long maxObjectsPerUserPerDay;
    private final long maxBytesPerUserPerDay;

    public UploadQuotaService(
            EphemeralStore ephemeralStore,
            @Value("${app.security.upload.max-objects-per-user-per-day:50}") long maxObjectsPerUserPerDay,
            @Value("${app.security.upload.max-bytes-per-user-per-day:104857600}") long maxBytesPerUserPerDay
    ) {
        if (maxObjectsPerUserPerDay < 1 || maxBytesPerUserPerDay < 1) {
            throw new IllegalStateException("Upload quota configuration must be positive");
        }
        this.ephemeralStore = ephemeralStore;
        this.maxObjectsPerUserPerDay = maxObjectsPerUserPerDay;
        this.maxBytesPerUserPerDay = maxBytesPerUserPerDay;
    }

    public void reserve(String userId, List<GeneratePresignedUrlsRequest.MediaFileInfo> mediaFiles)
            throws ChallengeSphereException {
        if (userId == null || userId.isBlank() || mediaFiles == null || mediaFiles.isEmpty()) {
            throw invalidRequest();
        }

        long requestedBytes = 0;
        for (GeneratePresignedUrlsRequest.MediaFileInfo file : mediaFiles) {
            if (file == null || file.getFileSizeBytes() == null || file.getFileSizeBytes() <= 0) {
                throw new ChallengeSphereException(
                        "File size is required",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
            try {
                requestedBytes = Math.addExact(requestedBytes, file.getFileSizeBytes());
            } catch (ArithmeticException overflow) {
                throw new ChallengeSphereException(
                        "Upload size is invalid",
                        HttpStatus.BAD_REQUEST,
                        logger
                );
            }
        }

        reserve(userId, mediaFiles.size(), requestedBytes);
    }

    public void reserve(String userId, long requestedObjects, long requestedBytes)
            throws ChallengeSphereException {
        if (userId == null || userId.isBlank() || requestedObjects <= 0 || requestedBytes <= 0) {
            throw invalidRequest();
        }

        Instant now = Instant.now();
        LocalDate utcDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant midnight = utcDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant expiresAt = Duration.between(now, midnight).compareTo(Duration.ofMinutes(1)) < 0
                ? now.plus(Duration.ofMinutes(1))
                : midnight;

        boolean reserved;
        try {
            reserved = ephemeralStore.reserveUploadQuota(
                    userId,
                    utcDate,
                    requestedObjects,
                    requestedBytes,
                    maxObjectsPerUserPerDay,
                    maxBytesPerUserPerDay,
                    expiresAt,
                    now
            );
        } catch (RuntimeException unavailable) {
            logger.error("Upload quota store is unavailable for userId={}: {}",
                    userId, unavailable.getClass().getSimpleName());
            throw new ChallengeSphereException(
                    "Upload protection is temporarily unavailable",
                    HttpStatus.SERVICE_UNAVAILABLE,
                    logger
            );
        }

        if (!reserved) {
            throw new ChallengeSphereException(
                    "Daily upload allowance reached. Please try again tomorrow.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    logger
            );
        }
    }

    private ChallengeSphereException invalidRequest() {
        return new ChallengeSphereException(
                "Upload quota request is invalid",
                HttpStatus.BAD_REQUEST,
                logger
        );
    }
}
