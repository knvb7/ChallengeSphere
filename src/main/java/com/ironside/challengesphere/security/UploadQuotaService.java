package com.ironside.challengesphere.security;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT;

    static {
        RESERVE_SCRIPT = new DefaultRedisScript<>();
        RESERVE_SCRIPT.setResultType(Long.class);
        RESERVE_SCRIPT.setScriptText("""
                local currentObjects = tonumber(redis.call('GET', KEYS[1]) or '0')
                local currentBytes = tonumber(redis.call('GET', KEYS[2]) or '0')
                local requestedObjects = tonumber(ARGV[1])
                local requestedBytes = tonumber(ARGV[2])
                local maxObjects = tonumber(ARGV[3])
                local maxBytes = tonumber(ARGV[4])
                local ttlMillis = tonumber(ARGV[5])

                if (currentObjects + requestedObjects > maxObjects)
                        or (currentBytes + requestedBytes > maxBytes) then
                    return 0
                end

                redis.call('INCRBY', KEYS[1], requestedObjects)
                redis.call('INCRBY', KEYS[2], requestedBytes)
                if redis.call('PTTL', KEYS[1]) < 0 then
                    redis.call('PEXPIRE', KEYS[1], ttlMillis)
                end
                if redis.call('PTTL', KEYS[2]) < 0 then
                    redis.call('PEXPIRE', KEYS[2], ttlMillis)
                end
                return 1
                """);
    }

    private final StringRedisTemplate redisTemplate;
    private final long maxObjectsPerUserPerDay;
    private final long maxBytesPerUserPerDay;

    public UploadQuotaService(
            StringRedisTemplate redisTemplate,
            @Value("${app.security.upload.max-objects-per-user-per-day:50}") long maxObjectsPerUserPerDay,
            @Value("${app.security.upload.max-bytes-per-user-per-day:104857600}") long maxBytesPerUserPerDay) {
        if (maxObjectsPerUserPerDay < 1 || maxBytesPerUserPerDay < 1) {
            throw new IllegalStateException("Upload quota configuration must be positive");
        }
        this.redisTemplate = redisTemplate;
        this.maxObjectsPerUserPerDay = maxObjectsPerUserPerDay;
        this.maxBytesPerUserPerDay = maxBytesPerUserPerDay;
    }

    public void reserve(Long userId, List<GeneratePresignedUrlsRequest.MediaFileInfo> mediaFiles)
            throws ChallengeSphereException {
        if (userId == null || userId <= 0 || mediaFiles == null || mediaFiles.isEmpty()) {
            throw new ChallengeSphereException(
                    "Upload quota request is invalid",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
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

    public void reserve(Long userId, long requestedObjects, long requestedBytes)
            throws ChallengeSphereException {
        if (userId == null
                || userId <= 0
                || requestedObjects <= 0
                || requestedBytes <= 0) {
            throw new ChallengeSphereException(
                    "Upload quota request is invalid",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        String day = LocalDate.now(ZoneOffset.UTC).toString();
        String keyPrefix = "upload-quota:{" + userId + "}:" + day + ":";
        long ttlMillis = Math.max(60_000L, Duration.between(
                Instant.now(),
                LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        ).toMillis());

        Long reserved;
        try {
            reserved = redisTemplate.execute(
                    RESERVE_SCRIPT,
                    List.of(keyPrefix + "objects", keyPrefix + "bytes"),
                    Long.toString(requestedObjects),
                    Long.toString(requestedBytes),
                    Long.toString(maxObjectsPerUserPerDay),
                    Long.toString(maxBytesPerUserPerDay),
                    Long.toString(ttlMillis)
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

        if (!Long.valueOf(1L).equals(reserved)) {
            throw new ChallengeSphereException(
                    "Daily upload allowance reached. Please try again tomorrow.",
                    HttpStatus.TOO_MANY_REQUESTS,
                    logger
            );
        }
    }
}
