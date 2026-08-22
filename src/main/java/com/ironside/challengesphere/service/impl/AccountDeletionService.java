package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.AccountDeletionResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.util.UserRole;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class AccountDeletionService {

    private static final Logger logger = LogManager.getLogger(AccountDeletionService.class);
    private static final String REQUIRED_CONFIRMATION = "DELETE";
    private static final Duration IDENTITY_HASH_RETENTION = Duration.ofDays(90);

    private static final String UPSERT_REQUEST_SQL = """
            INSERT INTO challengesphere.account_deletion_requests AS deletion_request (
                user_id,
                email_identity_hmac,
                username_identity_hmac,
                identity_hash_expires_at,
                identity_hash_purged_at,
                status,
                requested_at,
                processed_at,
                media_cleanup_count
            ) VALUES (?, ?, ?, ?, NULL, 'PROCESSING', CURRENT_TIMESTAMP, NULL, 0)
            ON CONFLICT (user_id) DO UPDATE SET
                email_identity_hmac = EXCLUDED.email_identity_hmac,
                username_identity_hmac = EXCLUDED.username_identity_hmac,
                identity_hash_expires_at = EXCLUDED.identity_hash_expires_at,
                identity_hash_purged_at = NULL,
                status = 'PROCESSING',
                requested_at = CURRENT_TIMESTAMP,
                processed_at = NULL,
                media_cleanup_count = 0
            RETURNING deletion_request.id
            """;

    private static final String MEDIA_REFERENCES_SQL = """
            SELECT 'PROFILE' AS source_type, id AS source_id, profile_pic_url AS media_references
            FROM challengesphere.users
            WHERE id = ? AND profile_pic_url IS NOT NULL AND BTRIM(profile_pic_url) <> ''
            UNION ALL
            SELECT 'SUBMISSION', id, media_urls
            FROM challengesphere.submissions
            WHERE user_id = ? AND media_urls IS NOT NULL AND BTRIM(media_urls) <> ''
            UNION ALL
            SELECT 'CHALLENGE', id, cover_photo_urls
            FROM challengesphere.challenges
            WHERE creator_id = ?
              AND private = TRUE
              AND cover_photo_urls IS NOT NULL
              AND BTRIM(cover_photo_urls) <> ''
            """;

    private static final String QUEUE_MEDIA_SQL = """
            INSERT INTO challengesphere.account_media_cleanup_queue (
                deletion_request_id,
                requested_by_user_id,
                source_type,
                source_id,
                object_key,
                status
            ) VALUES (?, ?, ?, ?, ?, 'PENDING')
            ON CONFLICT (requested_by_user_id, object_key) DO UPDATE SET
                deletion_request_id = EXCLUDED.deletion_request_id,
                source_type = EXCLUDED.source_type,
                source_id = EXCLUDED.source_id,
                status = 'PENDING',
                requested_at = CURRENT_TIMESTAMP,
                processed_at = NULL,
                failure_reason = NULL
            """;

    private static final List<String> USER_DATA_DELETE_SQL = List.of(
            "DELETE FROM challengesphere.challenge_peer_nudges WHERE sender_user_id = ? OR recipient_user_id = ?",
            "DELETE FROM challengesphere.challenge_peer_reminder_preferences WHERE user_id = ?",
            "DELETE FROM challengesphere.challenge_crew_invites WHERE invited_user_id = ? OR invited_by_user_id = ?",
            "UPDATE challengesphere.challenge_crew_lifecycle SET started_by_user_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE started_by_user_id = ?",
            "DELETE FROM challengesphere.comments WHERE user_id = ?",
            "DELETE FROM challengesphere.glories WHERE user_id = ?",
            "DELETE FROM challengesphere.reports WHERE user_id = ?",
            "DELETE FROM challengesphere.user_blocks WHERE blocker_user_id = ? OR blocked_user_id = ?",
            "DELETE FROM challengesphere.follows WHERE follower_id = ? OR following_id = ?",
            "DELETE FROM challengesphere.invite_usages WHERE user_id = ?",
            "DELETE FROM challengesphere.challenge_invites WHERE created_by = ?",
            "DELETE FROM challengesphere.leaderboard_entries WHERE user_id = ?",
            "DELETE FROM challengesphere.submissions WHERE user_id = ?",
            "DELETE FROM challengesphere.user_challenges WHERE user_id = ?",
            "DELETE FROM challengesphere.notifications WHERE user_id = ?",
            "DELETE FROM challengesphere.push_device_tokens WHERE user_id = ?",
            "DELETE FROM challengesphere.user_preferences WHERE user_id = ?",
            "DELETE FROM challengesphere.feature_requests WHERE user_id = ?",
            "DELETE FROM challengesphere.user_subscriptions WHERE user_id = ?",
            "DELETE FROM challengesphere.auth_identities WHERE user_id = ?"
    );

    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuthTokenStore authTokenStore;
    private final RedisCacheServiceImpl cacheService;

    @Value("${app.account-deletion.identity-hash-key}")
    private String identityHashKey;

    public AccountDeletionService(
            UserRepository userRepository,
            JdbcTemplate jdbcTemplate,
            AuthTokenStore authTokenStore,
            RedisCacheServiceImpl cacheService) {
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.authTokenStore = authTokenStore;
        this.cacheService = cacheService;
    }

    @PostConstruct
    void validateIdentityHashConfiguration() {
        if (identityHashKey == null || identityHashKey.length() < 32) {
            throw new IllegalStateException(
                    "app.account-deletion.identity-hash-key must contain at least 32 characters"
            );
        }
    }

    @Transactional
    public AccountDeletionResponse deleteAccount(Long userId, String confirmation)
            throws ChallengeSphereException {
        if (userId == null) {
            throw new ChallengeSphereException("Authenticated user required", HttpStatus.UNAUTHORIZED, logger);
        }
        if (!REQUIRED_CONFIRMATION.equals(confirmation == null ? "" : confirmation.trim().toUpperCase(Locale.ROOT))) {
            throw new ChallengeSphereException(
                    "Type DELETE to confirm account deletion",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }

        return deleteAccountInternal(userId, true);
    }

    @Transactional
    public void deleteAccountFromAppleNotification(Long userId) throws ChallengeSphereException {
        if (userId == null) {
            return;
        }
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null || user.getDeletedAt() != null || !Boolean.TRUE.equals(user.getActive())) {
            return;
        }
        deleteAccountInternal(userId, false);
    }

    private AccountDeletionResponse deleteAccountInternal(Long userId, boolean queueAppleRevocation)
            throws ChallengeSphereException {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ChallengeSphereException("User not found", HttpStatus.NOT_FOUND, logger));
        if (user.getDeletedAt() != null || !Boolean.TRUE.equals(user.getActive())) {
            throw new ChallengeSphereException("This account is already disabled", HttpStatus.CONFLICT, logger);
        }

        Long requestId = jdbcTemplate.queryForObject(
                UPSERT_REQUEST_SQL,
                Long.class,
                userId,
                pseudonymizeIdentity("email", user.getEmailId()),
                pseudonymizeIdentity("username", user.getUsername()),
                Timestamp.from(Instant.now().plus(IDENTITY_HASH_RETENTION))
        );
        if (requestId == null) {
            throw new ChallengeSphereException(
                    "Could not create the account deletion request",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    logger
            );
        }

        int queuedMedia = queueOwnedMediaReferences(requestId, userId);
        if (queueAppleRevocation) {
            queueProviderTokenRevocations(userId);
        }
        deactivatePrivateCreatedChallenges(userId);
        redactSafetyRecords(userId);
        deleteAssociatedRows(userId);
        repairAggregateCounts();
        anonymizeUser(user);
        authTokenStore.revokeAllForUser(userId);

        jdbcTemplate.update(
                """
                UPDATE challengesphere.account_deletion_requests
                SET status = 'COMPLETED', processed_at = CURRENT_TIMESTAMP, media_cleanup_count = ?
                WHERE id = ?
                """,
                queuedMedia,
                requestId
        );

        safeInvalidateCaches(userId);
        logger.info("Account deletion completed for userId={} with {} media object(s) queued", userId, queuedMedia);
        return new AccountDeletionResponse(
                true,
                "Account deleted. ChallengeSphere keeps keyed, non-readable fingerprints of your "
                        + "email and username for up to 90 days for deletion auditing and abuse prevention; "
                        + "your original values and display name are not retained in that record. "
                        + "Private challenges you created are taken offline, "
                        + "while anonymized public challenges and their cover media remain available for their "
                        + "participants. Profile, submission, and private-challenge media are queued for "
                        + "controlled cleanup.",
                queuedMedia
        );
    }

    private int queueOwnedMediaReferences(Long requestId, Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                MEDIA_REFERENCES_SQL,
                userId,
                userId,
                userId
        );
        List<MediaReference> references = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sourceType = String.valueOf(row.get("source_type"));
            Object rawSourceId = row.get("source_id");
            long sourceId = rawSourceId instanceof Number number ? number.longValue() : userId;
            String rawReferences = String.valueOf(row.get("media_references"));
            for (String value : rawReferences.split(",")) {
                String objectKey = value.trim();
                if (isOwnedObjectKey(objectKey, userId)) {
                    references.add(new MediaReference(sourceType, sourceId, objectKey));
                }
            }
        }

        int queued = 0;
        for (MediaReference reference : references.stream().distinct().toList()) {
            jdbcTemplate.update(
                    QUEUE_MEDIA_SQL,
                    requestId,
                    userId,
                    reference.sourceType(),
                    reference.sourceId(),
                    reference.objectKey()
            );
            queued++;
        }
        return queued;
    }

    private String pseudonymizeIdentity(String field, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(rawValue.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    identityHashKey.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            byte[] digest = mac.doFinal(
                    (field + ":" + normalized).getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to pseudonymize deleted account identity", exception);
        }
    }

    private void queueProviderTokenRevocations(Long userId) {
        jdbcTemplate.update(
                """
                INSERT INTO challengesphere.provider_token_revocations (
                    provider,
                    encrypted_token,
                    status,
                    attempts,
                    next_attempt_at
                )
                SELECT provider, encrypted_refresh_token, 'PENDING', 0, CURRENT_TIMESTAMP
                FROM challengesphere.auth_identities
                WHERE user_id = ?
                  AND provider = 'APPLE'
                  AND encrypted_refresh_token IS NOT NULL
                  AND BTRIM(encrypted_refresh_token) <> ''
                """,
                userId
        );
    }

    private boolean isOwnedObjectKey(String objectKey, Long userId) {
        if (objectKey == null || objectKey.isBlank()) {
            return false;
        }
        return objectKey.startsWith("profiles/user_" + userId + "/")
                || objectKey.startsWith("submissions/user_" + userId + "/")
                || objectKey.startsWith("challenges/user_" + userId + "/covers/");
    }

    private void deactivatePrivateCreatedChallenges(Long userId) {
        jdbcTemplate.update(
                """
                UPDATE challengesphere.challenges
                SET active = FALSE
                WHERE private = TRUE
                  AND creator_id = ?
                """,
                userId
        );
    }

    private void redactSafetyRecords(Long userId) {
        jdbcTemplate.update(
                """
                UPDATE challengesphere.user_reports
                SET details = NULL
                WHERE reporter_user_id = ? OR reported_user_id = ?
                """,
                userId,
                userId
        );
        jdbcTemplate.update(
                """
                UPDATE challengesphere.content_reports
                SET details = NULL
                WHERE reporter_user_id = ? OR target_owner_user_id = ?
                """,
                userId,
                userId
        );
        jdbcTemplate.update(
                """
                UPDATE challengesphere.notifications
                SET message = 'Activity from a deleted account', action_object = NULL
                WHERE action_object LIKE ?
                """,
                "%\"actorUserId\":" + userId + "%"
        );
    }

    private void deleteAssociatedRows(Long userId) {
        for (String sql : USER_DATA_DELETE_SQL) {
            int placeholders = (int) sql.chars().filter(character -> character == '?').count();
            if (placeholders == 2) {
                jdbcTemplate.update(sql, userId, userId);
            } else {
                jdbcTemplate.update(sql, userId);
            }
        }
    }

    private void repairAggregateCounts() {
        jdbcTemplate.update("""
                UPDATE challengesphere.submissions submission
                SET comments_count = (
                        SELECT COUNT(*) FROM challengesphere.comments comment
                        WHERE comment.submission_id = submission.id AND comment.active = TRUE
                    ),
                    glories_count = (
                        SELECT COUNT(*) FROM challengesphere.glories glory
                        WHERE glory.submission_id = submission.id
                          AND glory.active = TRUE
                          AND COALESCE(glory.ungloried, FALSE) = FALSE
                    )
                WHERE submission.comments_count <> (
                        SELECT COUNT(*) FROM challengesphere.comments comment
                        WHERE comment.submission_id = submission.id AND comment.active = TRUE
                    )
                   OR submission.glories_count <> (
                        SELECT COUNT(*) FROM challengesphere.glories glory
                        WHERE glory.submission_id = submission.id
                          AND glory.active = TRUE
                          AND COALESCE(glory.ungloried, FALSE) = FALSE
                    )
                """);
        jdbcTemplate.update("""
                UPDATE challengesphere.users account
                SET followers = (
                        SELECT COUNT(*) FROM challengesphere.follows follow_row
                        WHERE follow_row.following_id = account.id AND follow_row.active = TRUE
                    ),
                    following = (
                        SELECT COUNT(*) FROM challengesphere.follows follow_row
                        WHERE follow_row.follower_id = account.id AND follow_row.active = TRUE
                    )
                """);
    }

    private void anonymizeUser(User user) {
        Long userId = user.getId();
        Timestamp now = Timestamp.from(Instant.now());
        user.setEmailId("deleted+" + userId + "+" + UUID.randomUUID() + "@deleted.invalid");
        user.setPhone(null);
        user.setUsername("deleted_user_" + userId);
        user.setName(null);
        user.setHashedPassword(null);
        user.setGoogleId(null);
        user.setProfilePicUrl(null);
        user.setBio(null);
        user.setDateOfBirth(null);
        user.setAge(null);
        user.setGender(null);
        user.setCountry(null);
        user.setInterests(null);
        user.setLastLoginAt(null);
        user.setBannedAt(null);
        user.setBanReason(null);
        user.setGenuinenessScore(0);
        user.setPremiumUser(0);
        user.setRole(UserRole.USER);
        user.setNotifications(0);
        user.setChallengesCreated(0);
        user.setChallengesAccepted(0);
        user.setFollowers(0);
        user.setFollowing(0);
        user.setActive(false);
        user.setDeletedAt(now);
        userRepository.saveAndFlush(user);
    }

    private void safeInvalidateCaches(Long userId) {
        try {
            List<Long> challengeIds = jdbcTemplate.queryForList(
                    """
                    SELECT id
                    FROM challengesphere.challenges
                    WHERE creator_id = ?
                    """,
                    Long.class,
                    userId
            );
            challengeIds.forEach(cacheService::clearChallengeCache);
            cacheService.invalidateAllUserCaches(userId);
            cacheService.invalidateAllFeeds();
            cacheService.invalidateRecentAndTrendingChallenges();
        } catch (Exception e) {
            logger.warn("Account deletion cache invalidation failed for userId={}: {}",
                    userId, e.getClass().getSimpleName());
        }
    }

    private record MediaReference(String sourceType, long sourceId, String objectKey) {
    }
}
