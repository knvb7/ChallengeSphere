package com.ironside.challengesphere.security;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Records newly issued upload keys and atomically consumes them when media is
 * attached. Keys therefore cannot be fabricated, replayed, or attached twice.
 */
@Service
public class IssuedUploadService {

    public enum Purpose {
        SUBMISSION_PHOTO,
        CHALLENGE_COVER,
        PROFILE_PHOTO,
        SUPPORT_SCREENSHOT
    }

    private static final Logger logger = LogManager.getLogger(IssuedUploadService.class);
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT;

    static {
        CONSUME_SCRIPT = new DefaultRedisScript<>();
        CONSUME_SCRIPT.setResultType(Long.class);
        CONSUME_SCRIPT.setScriptText("""
                local expectedPurpose = ARGV[1]

                for index = 1, #KEYS do
                    local expectedValue = expectedPurpose .. '|' .. ARGV[index + 1]
                    local value = redis.call('GET', KEYS[index])
                    if value ~= expectedValue then
                        return 0
                    end
                end

                for index = 1, #KEYS do
                    redis.call('DEL', KEYS[index])
                end
                return 1
                """);
    }

    private final StringRedisTemplate redisTemplate;
    private final Duration ticketTtl;

    public IssuedUploadService(
            StringRedisTemplate redisTemplate,
            @Value("${app.security.upload.ticket-ttl-minutes:60}") long ticketTtlMinutes) {
        if (ticketTtlMinutes < 1 || ticketTtlMinutes > 1_440) {
            throw new IllegalStateException("Upload ticket TTL must be between 1 and 1440 minutes");
        }
        this.redisTemplate = redisTemplate;
        this.ticketTtl = Duration.ofMinutes(ticketTtlMinutes);
    }

    public void issue(Long userId, String objectKey, Purpose purpose)
            throws ChallengeSphereException {
        validate(userId, objectKey, purpose);
        String value = purpose.name() + "|" + objectKey;
        try {
            Boolean issued = redisTemplate.opsForValue().setIfAbsent(
                    ticketKey(userId, objectKey), value, ticketTtl);
            if (!Boolean.TRUE.equals(issued)) {
                throw new ChallengeSphereException(
                        "Upload key could not be issued",
                        HttpStatus.CONFLICT,
                        logger
                );
            }
        } catch (ChallengeSphereException e) {
            throw e;
        } catch (RuntimeException unavailable) {
            logger.error("Upload ticket store is unavailable while issuing for userId={}: {}",
                    userId, unavailable.getClass().getSimpleName());
            throw unavailable();
        }
    }

    public void consume(Long userId, List<String> objectKeys, Purpose purpose)
            throws ChallengeSphereException {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        if (objectKeys.size() > 5 || objectKeys.stream().distinct().count() != objectKeys.size()) {
            throw invalidReference();
        }
        for (String objectKey : objectKeys) {
            validate(userId, objectKey, purpose);
        }

        List<String> ticketKeys = objectKeys.stream()
                .map(objectKey -> ticketKey(userId, objectKey))
                .toList();
        Object[] arguments = new Object[objectKeys.size() + 1];
        arguments[0] = purpose.name();
        for (int index = 0; index < objectKeys.size(); index++) {
            arguments[index + 1] = objectKeys.get(index);
        }

        Long consumed;
        try {
            consumed = redisTemplate.execute(
                    CONSUME_SCRIPT,
                    ticketKeys,
                    arguments
            );
        } catch (RuntimeException unavailable) {
            logger.error("Upload ticket store is unavailable while consuming for userId={}: {}",
                    userId, unavailable.getClass().getSimpleName());
            throw unavailable();
        }
        if (!Long.valueOf(1L).equals(consumed)) {
            throw invalidReference();
        }
    }

    private void validate(Long userId, String objectKey, Purpose purpose)
            throws ChallengeSphereException {
        if (userId == null
                || userId <= 0
                || purpose == null
                || objectKey == null
                || objectKey.isBlank()
                || objectKey.length() > 256
                || objectKey.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidReference();
        }
    }

    private String ticketKey(Long userId, String objectKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectKey.getBytes(StandardCharsets.UTF_8));
            return "upload-issued:{" + userId + "}:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private ChallengeSphereException invalidReference() {
        return new ChallengeSphereException(
                "Upload reference is invalid, expired, or already used",
                HttpStatus.BAD_REQUEST,
                logger
        );
    }

    private ChallengeSphereException unavailable() {
        return new ChallengeSphereException(
                "Upload protection is temporarily unavailable",
                HttpStatus.SERVICE_UNAVAILABLE,
                logger
        );
    }
}
