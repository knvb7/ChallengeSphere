package com.ironside.challengesphere.persistence.dynamodb.repository.user;

import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoAccessPatterns;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbKeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.Locale;

/** Key vocabulary shared by the user and social repositories. */
public final class UserDynamoKeys {

    public static final String LOOKUP = "LOOKUP";
    public static final String ITEM_PREFIX = "ITEM#";
    public static final int MAX_QUERY_ITEMS = 100_000;

    private UserDynamoKeys() {
    }

    public static DynamoProjection projection(String partitionKey, String sortKey) {
        return DynamoProjection.of(partitionKey, sortKey);
    }

    public static String collection(String type, String state) {
        return DynamoAccessPatterns.collectionPk(type, state);
    }

    public static String uniquePk(String entityType, String field, String value) {
        return uniquePkExact(entityType, field, normalize(value));
    }

    public static String uniquePkExact(String entityType, String field, String value) {
        return DynamoDbKeys.join(
                "UNIQUE",
                entityType.toUpperCase(Locale.ROOT),
                field.toUpperCase(Locale.ROOT),
                sha256(require(value, "value").strip())
        );
    }

    public static String exactPairPk(String relationship, String firstId, String secondId) {
        return DynamoDbKeys.join(
                "RELATION",
                relationship.toUpperCase(Locale.ROOT),
                require(firstId, "firstId"),
                require(secondId, "secondId")
        );
    }

    public static String ownerCollectionPk(String collection, String ownerId) {
        return DynamoDbKeys.join(
                collection.toUpperCase(Locale.ROOT),
                require(ownerId, "ownerId")
        );
    }

    public static String rankSk(int followers, Timestamp lastLoginAt, String username, String id) {
        long invertedFollowers = (long) Integer.MAX_VALUE - Math.max(0, followers);
        long loginMillis = timestampMillis(lastLoginAt);
        long invertedLogin = Long.MAX_VALUE - Math.max(0L, loginMillis);
        return String.format(
                Locale.ROOT,
                "RANK#%010d#%019d#%s#%s",
                invertedFollowers,
                invertedLogin,
                normalize(username == null ? id : username),
                require(id, "id")
        );
    }

    public static String recentSk(Timestamp timestamp, String id) {
        return String.format(
                Locale.ROOT,
                "LOGIN#%013d#%s",
                timestampMillis(timestamp),
                require(id, "id")
        );
    }

    public static String chronologicalSk(String prefix, Timestamp timestamp, String id) {
        return String.format(
                Locale.ROOT,
                "%s#%019d#%s",
                require(prefix, "prefix").toUpperCase(Locale.ROOT),
                timestampMillis(timestamp),
                require(id, "id")
        );
    }

    public static String normalize(String value) {
        return DynamoAccessPatterns.normalized(value);
    }

    public static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : normalize(value);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(require(value, "value").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                hex.append(String.format("%02x", part));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static long timestampMillis(Timestamp timestamp) {
        return timestamp == null ? 0L : timestamp.getTime();
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
