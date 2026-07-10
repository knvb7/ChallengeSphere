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

@Component
public class SubmissionMediaKeyPolicy {

    private static final Logger logger = LogManager.getLogger(SubmissionMediaKeyPolicy.class);
    private static final int MAX_MEDIA_KEYS = 5;
    private static final Pattern KEY_PATTERN = Pattern.compile(
            "^submissions/user_(\\d+)/(SUBMISSION_PHOTO)/"
                    + "\\d{13}_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|jpeg|png|gif)$");
    private static final Pattern READ_KEY_PATTERN = Pattern.compile(
            "^submissions/user_(\\d+)/(SUBMISSION_PHOTO|SUBMISSION_VIDEO)/"
                    + "\\d{13}_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|jpeg|png|gif|mp4)$");

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

    public List<String> filterOwnedKeysForRead(Long userId, String rawMediaKeys) {
        if (userId == null
                || rawMediaKeys == null
                || rawMediaKeys.isBlank()
                || rawMediaKeys.length() > 1_024) {
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        List<String> filtered = new ArrayList<>(MAX_MEDIA_KEYS);
        for (String raw : rawMediaKeys.split(",", -1)) {
            if (filtered.size() >= MAX_MEDIA_KEYS) {
                break;
            }
            String key = raw.trim();
            Matcher matcher = READ_KEY_PATTERN.matcher(key);
            if (!matcher.matches()
                    || !Long.toString(userId).equals(matcher.group(1))
                    || !readExtensionMatchesType(matcher.group(2), matcher.group(3))
                    || !seen.add(key)) {
                continue;
            }
            filtered.add(key);
        }
        return List.copyOf(filtered);
    }

    private boolean readExtensionMatchesType(String type, String extension) {
        return "SUBMISSION_VIDEO".equals(type)
                ? "mp4".equals(extension)
                : !"mp4".equals(extension);
    }

    public String createKey(
            Long userId,
            MediaTypes mediaType,
            GeneratePresignedUrlsRequest.ContentType contentType,
            long epochMillis,
            UUID uniqueId) throws ChallengeSphereException {
        if (userId == null || userId <= 0 || mediaType == null || contentType == null || uniqueId == null
                || epochMillis < 1_000_000_000_000L
                || epochMillis > 9_999_999_999_999L
                || mediaType != MediaTypes.SUBMISSION_PHOTO
                || contentType == GeneratePresignedUrlsRequest.ContentType.VIDEO_MP4) {
            throw invalid();
        }

        String extension = switch (contentType) {
            case IMAGE_JPEG -> ".jpg";
            case IMAGE_PNG -> ".png";
            case IMAGE_GIF -> ".gif";
            case VIDEO_MP4 -> throw invalid();
        };
        return "submissions/user_%d/%s/%d_%s%s".formatted(
                userId, mediaType.name(), epochMillis, uniqueId, extension);
    }

    private ChallengeSphereException invalid() {
        return new ChallengeSphereException(
                "Invalid submission media reference", HttpStatus.BAD_REQUEST, logger);
    }
}
