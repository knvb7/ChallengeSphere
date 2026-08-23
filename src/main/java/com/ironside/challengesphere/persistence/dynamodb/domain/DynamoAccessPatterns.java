package com.ironside.challengesphere.persistence.dynamodb.domain;

import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;

/** Central key vocabulary for the ChallengeSphere single-table model. */
public final class DynamoAccessPatterns {

    public static final String META = "META";
    public static final int SEARCH_SHARDS = 4;
    public static final int PUBLIC_FEED_SHARDS = 8;

    private DynamoAccessPatterns() {
    }

    public static String entityPk(String type, String id) {
        return DynamoDbKeys.join(normalizeType(type), require(id, "id"));
    }

    public static String collectionPk(String type, String state) {
        return DynamoDbKeys.join("COLLECTION", normalizeType(type), normalizeType(state));
    }

    public static String ownerPk(String ownerType, String ownerId) {
        return DynamoDbKeys.join(normalizeType(ownerType), require(ownerId, "ownerId"));
    }

    public static String chronologicalSk(String type, Instant timestamp, String id) {
        return DynamoDbKeys.join(
                normalizeType(type),
                require(timestamp, "timestamp").toString(),
                require(id, "id")
        );
    }

    public static String dateSk(String type, LocalDate date, String id) {
        return DynamoDbKeys.join(
                normalizeType(type),
                require(date, "date").toString(),
                require(id, "id")
        );
    }

    public static String edgeSk(String edgeType, String targetId) {
        return DynamoDbKeys.join(normalizeType(edgeType), require(targetId, "targetId"));
    }

    public static String normalized(String value) {
        return require(value, "value").strip().toLowerCase(Locale.ROOT);
    }

    public static String searchPk(String type, int shard) {
        if (shard < 0 || shard >= SEARCH_SHARDS) {
            throw new IllegalArgumentException("search shard is out of range");
        }
        return DynamoDbKeys.join("SEARCH", normalizeType(type), String.format(Locale.ROOT, "%02d", shard));
    }

    public static String searchSk(String normalizedText, String id) {
        return DynamoDbKeys.join(normalized(normalizedText), require(id, "id"));
    }

    public static String exactTagPk(String tagType, String value) {
        return DynamoDbKeys.join("TAG", normalizeType(tagType), normalized(value));
    }

    public static String userFeedPk(String userId, String feedType) {
        return DynamoDbKeys.join("FEED", "USER", require(userId, "userId"), normalizeType(feedType));
    }

    public static String publicFeedPk(String feedType, int shard) {
        if (shard < 0 || shard >= PUBLIC_FEED_SHARDS) {
            throw new IllegalArgumentException("public feed shard is out of range");
        }
        return DynamoDbKeys.join("FEED", "PUBLIC", normalizeType(feedType), String.format(Locale.ROOT, "%02d", shard));
    }

    public static int stableShard(String id, int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(require(id, "id").getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        int prefix = ((digest[0] & 0xff) << 24)
                | ((digest[1] & 0xff) << 16)
                | ((digest[2] & 0xff) << 8)
                | (digest[3] & 0xff);
        return Math.floorMod(prefix, shardCount);
    }

    private static String normalizeType(String type) {
        return normalized(type).toUpperCase(Locale.ROOT);
    }

    private static <T> T require(T value, String name) {
        if (value == null || value instanceof String text && text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
