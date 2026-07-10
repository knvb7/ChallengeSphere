package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.request.GeneratePresignedUrlsRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.util.MediaTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defines the only S3 object-key namespace that a user may attach to a challenge.
 * Keeping this policy separate prevents arbitrary bucket keys and external URLs
 * from being turned into server-signed reads through challenge responses.
 */
@Component
public class ChallengeMediaKeyPolicy {

    private static final Logger logger = LogManager.getLogger(ChallengeMediaKeyPolicy.class);
    private static final int MAX_MEDIA_KEYS = 5;
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^challenges/user_(\\d+)/covers/\\d{13}_"
                    + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|png|gif)$");

    public List<String> validateAndNormalize(Long userId, List<String> mediaKeys)
            throws ChallengeSphereException {
        if (userId == null || userId <= 0) {
            throw invalid();
        }
        if (mediaKeys == null || mediaKeys.isEmpty()) {
            return List.of();
        }
        if (mediaKeys.size() > MAX_MEDIA_KEYS) {
            throw invalid();
        }

        Set<String> seen = new HashSet<>();
        List<String> normalized = new ArrayList<>(mediaKeys.size());
        for (String raw : mediaKeys) {
            if (raw == null || raw.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid();
            }
            String key = raw.trim();
            Matcher matcher = KEY_PATTERN.matcher(key);
            if (!matcher.matches()
                    || !Long.toString(userId).equals(matcher.group(1))
                    || !seen.add(key)) {
                throw invalid();
            }
            normalized.add(key);
        }
        return List.copyOf(normalized);
    }

    /**
     * Filters legacy database values at the read boundary. Invalid, external, or
     * cross-user references are omitted instead of being signed or returned.
     */
    public List<String> filterOwnedKeysForRead(String rawMediaKeys, Set<Long> allowedOwnerIds) {
        if (rawMediaKeys == null
                || rawMediaKeys.isBlank()
                || rawMediaKeys.length() > 1_024
                || allowedOwnerIds == null
                || allowedOwnerIds.isEmpty()) {
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        List<String> filtered = new ArrayList<>(MAX_MEDIA_KEYS);
        for (String raw : rawMediaKeys.split(",", -1)) {
            if (filtered.size() >= MAX_MEDIA_KEYS || raw == null) {
                break;
            }
            String key = raw.trim();
            Matcher matcher = KEY_PATTERN.matcher(key);
            if (!matcher.matches() || !seen.add(key)) {
                continue;
            }
            try {
                if (allowedOwnerIds.contains(Long.valueOf(matcher.group(1)))) {
                    filtered.add(key);
                }
            } catch (NumberFormatException ignored) {
                // The regex bounds the format; an overflowing legacy id is simply invalid.
            }
        }
        return List.copyOf(filtered);
    }

    public String createKey(
            Long userId,
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType,
            long epochMillis,
            UUID uniqueId) throws ChallengeSphereException {
        if (userId == null || userId <= 0
                || mediaType != MediaTypes.CHALLENGE_PHOTO
                || contentType == null
                || contentType == GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4
                || uniqueId == null
                || epochMillis < 1_000_000_000_000L
                || epochMillis > 9_999_999_999_999L) {
            throw invalid();
        }

        String extension = switch (contentType) {
            case IMAGE_JPEG -> ".jpg";
            case IMAGE_PNG -> ".png";
            case IMAGE_GIF -> ".gif";
            case VIDEO_MP4 -> throw invalid();
        };
        return "challenges/user_%d/covers/%d_%s%s".formatted(
                userId, epochMillis, uniqueId, extension);
    }

    private ChallengeSphereException invalid() {
        return new ChallengeSphereException(
                "Invalid challenge cover media reference", HttpStatus.BAD_REQUEST, logger);
    }
}
