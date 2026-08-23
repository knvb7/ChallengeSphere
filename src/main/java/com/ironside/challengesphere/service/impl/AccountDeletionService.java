package com.ironside.challengesphere.service.impl;

import com.ironside.challengesphere.api.response.AccountDeletionResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.AuthIdentity;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeCrewInvite;
import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
import com.ironside.challengesphere.model.ChallengeInvite;
import com.ironside.challengesphere.model.ChallengePeerNudge;
import com.ironside.challengesphere.model.ChallengePeerReminderPreference;
import com.ironside.challengesphere.model.Comment;
import com.ironside.challengesphere.model.FeatureRequest;
import com.ironside.challengesphere.model.Follow;
import com.ironside.challengesphere.model.Glory;
import com.ironside.challengesphere.model.InviteUsage;
import com.ironside.challengesphere.model.LeaderboardEntry;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.model.Report;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserBlock;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.model.UserReport;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.user.UserDynamoKeys;
import com.ironside.challengesphere.repository.AuthIdentityRepository;
import com.ironside.challengesphere.repository.ChallengeCrewInviteRepository;
import com.ironside.challengesphere.repository.ChallengeCrewLifecycleRepository;
import com.ironside.challengesphere.repository.ChallengeInviteRepository;
import com.ironside.challengesphere.repository.ChallengePeerNudgeRepository;
import com.ironside.challengesphere.repository.ChallengePeerReminderPreferenceRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.CommentRepository;
import com.ironside.challengesphere.repository.FeatureRequestRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.GloryRepository;
import com.ironside.challengesphere.repository.InviteUsageRepository;
import com.ironside.challengesphere.repository.LeaderboardEntryRepository;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.PushDeviceTokenRepository;
import com.ironside.challengesphere.repository.ReportRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserReportRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.repository.UserSubscriptionRepository;
import com.ironside.challengesphere.security.AuthTokenStore;
import com.ironside.challengesphere.util.UserRole;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Account erasure orchestration backed only by keyed DynamoDB access patterns. */
@Service
public class AccountDeletionService {

    private static final Logger logger = LogManager.getLogger(AccountDeletionService.class);
    private static final String REQUIRED_CONFIRMATION = "DELETE";
    private static final Duration IDENTITY_HASH_RETENTION = Duration.ofDays(90);
    private static final Duration DELETION_LEASE = Duration.ofMinutes(15);
    private static final int DELETION_PAGE_SIZE = 100;
    private static final String DELETED_ACTIVITY_MESSAGE = "Activity from a deleted account";

    private final UserRepository userRepository;
    private final DynamoDomainStore domainStore;
    private final DynamoOperationalStore operationalStore;
    private final AuthTokenStore authTokenStore;
    private final RedisCacheServiceImpl cacheService;
    private final AuthIdentityRepository authIdentityRepository;
    private final ChallengeRepository challengeRepository;
    private final SubmissionRepository submissionRepository;
    private final CommentRepository commentRepository;
    private final GloryRepository gloryRepository;
    private final ReportRepository reportRepository;
    private final UserReportRepository userReportRepository;
    private final UserBlockRepository userBlockRepository;
    private final FollowRepository followRepository;
    private final InviteUsageRepository inviteUsageRepository;
    private final ChallengeInviteRepository challengeInviteRepository;
    private final LeaderboardEntryRepository leaderboardEntryRepository;
    private final UserChallengeRepository userChallengeRepository;
    private final NotificationRepository notificationRepository;
    private final PushDeviceTokenRepository pushDeviceTokenRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final FeatureRequestRepository featureRequestRepository;
    private final UserSubscriptionRepository userSubscriptionRepository;
    private final ChallengePeerNudgeRepository peerNudgeRepository;
    private final ChallengePeerReminderPreferenceRepository peerReminderRepository;
    private final ChallengeCrewInviteRepository crewInviteRepository;
    private final ChallengeCrewLifecycleRepository crewLifecycleRepository;

    @Value("${app.account-deletion.identity-hash-key}")
    private String identityHashKey;

    public AccountDeletionService(
            UserRepository userRepository,
            DynamoDomainStore domainStore,
            DynamoOperationalStore operationalStore,
            AuthTokenStore authTokenStore,
            RedisCacheServiceImpl cacheService,
            AuthIdentityRepository authIdentityRepository,
            ChallengeRepository challengeRepository,
            SubmissionRepository submissionRepository,
            CommentRepository commentRepository,
            GloryRepository gloryRepository,
            ReportRepository reportRepository,
            UserReportRepository userReportRepository,
            UserBlockRepository userBlockRepository,
            FollowRepository followRepository,
            InviteUsageRepository inviteUsageRepository,
            ChallengeInviteRepository challengeInviteRepository,
            LeaderboardEntryRepository leaderboardEntryRepository,
            UserChallengeRepository userChallengeRepository,
            NotificationRepository notificationRepository,
            PushDeviceTokenRepository pushDeviceTokenRepository,
            UserPreferencesRepository userPreferencesRepository,
            FeatureRequestRepository featureRequestRepository,
            UserSubscriptionRepository userSubscriptionRepository,
            ChallengePeerNudgeRepository peerNudgeRepository,
            ChallengePeerReminderPreferenceRepository peerReminderRepository,
            ChallengeCrewInviteRepository crewInviteRepository,
            ChallengeCrewLifecycleRepository crewLifecycleRepository
    ) {
        this.userRepository = userRepository;
        this.domainStore = domainStore;
        this.operationalStore = operationalStore;
        this.authTokenStore = authTokenStore;
        this.cacheService = cacheService;
        this.authIdentityRepository = authIdentityRepository;
        this.challengeRepository = challengeRepository;
        this.submissionRepository = submissionRepository;
        this.commentRepository = commentRepository;
        this.gloryRepository = gloryRepository;
        this.reportRepository = reportRepository;
        this.userReportRepository = userReportRepository;
        this.userBlockRepository = userBlockRepository;
        this.followRepository = followRepository;
        this.inviteUsageRepository = inviteUsageRepository;
        this.challengeInviteRepository = challengeInviteRepository;
        this.leaderboardEntryRepository = leaderboardEntryRepository;
        this.userChallengeRepository = userChallengeRepository;
        this.notificationRepository = notificationRepository;
        this.pushDeviceTokenRepository = pushDeviceTokenRepository;
        this.userPreferencesRepository = userPreferencesRepository;
        this.featureRequestRepository = featureRequestRepository;
        this.userSubscriptionRepository = userSubscriptionRepository;
        this.peerNudgeRepository = peerNudgeRepository;
        this.peerReminderRepository = peerReminderRepository;
        this.crewInviteRepository = crewInviteRepository;
        this.crewLifecycleRepository = crewLifecycleRepository;
    }

    @PostConstruct
    void validateIdentityHashConfiguration() {
        if (identityHashKey == null || identityHashKey.length() < 32) {
            throw new IllegalStateException(
                    "app.account-deletion.identity-hash-key must contain at least 32 characters"
            );
        }
    }

    public AccountDeletionResponse deleteAccount(String userId, String confirmation)
            throws ChallengeSphereException {
        if (userId == null || userId.isBlank()) {
            throw new ChallengeSphereException(
                    "Authenticated user required", HttpStatus.UNAUTHORIZED, logger
            );
        }
        String normalizedConfirmation = confirmation == null
                ? ""
                : confirmation.trim().toUpperCase(Locale.ROOT);
        if (!REQUIRED_CONFIRMATION.equals(normalizedConfirmation)) {
            throw new ChallengeSphereException(
                    "Type DELETE to confirm account deletion",
                    HttpStatus.BAD_REQUEST,
                    logger
            );
        }
        return deleteAccountInternal(userId, true);
    }

    public void deleteAccountFromAppleNotification(String userId) throws ChallengeSphereException {
        if (userId == null || userId.isBlank()) {
            return;
        }
        User user = userRepository.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return;
        }
        if (user.getDeletedAt() == null
                && !Boolean.TRUE.equals(user.getActive())
                && operationalStore.findAccountDeletionCheckpoint(userId).isEmpty()) {
            return;
        }
        deleteAccountInternal(userId, false);
    }

    private AccountDeletionResponse deleteAccountInternal(String userId, boolean queueAppleRevocation)
            throws ChallengeSphereException {
        DynamoOperationalStore.Lease lease = operationalStore.tryAcquireLease(
                        "ACCOUNT_DELETION", userId, DELETION_LEASE
                )
                .orElseThrow(() -> new ChallengeSphereException(
                        "Account deletion is already being processed",
                        HttpStatus.CONFLICT,
                        logger
                ));
        try {
            User user = requireDeletionUser(userId);
            DynamoOperationalStore.AccountDeletionCheckpoint checkpoint = operationalStore
                    .findAccountDeletionCheckpoint(userId)
                    .orElse(null);
            if (user.getDeletedAt() != null && checkpoint == null) {
                // Historical tombstones may predate the Dynamo workflow. Token
                // cleanup is still safe and idempotent, but never fabricate a
                // retention record from already-anonymized identity values.
                authTokenStore.revokeAllForUser(userId);
                return completedResponse(0);
            }
            if (user.getDeletedAt() == null
                    && !Boolean.TRUE.equals(user.getActive())
                    && checkpoint == null) {
                throw new ChallengeSphereException(
                        "This account is already disabled", HttpStatus.CONFLICT, logger
                );
            }
            String requestId = checkpoint == null
                    ? operationalStore.upsertAccountDeletionRetention(
                            userId,
                            pseudonymizeIdentity("email", user.getEmailId()),
                            pseudonymizeIdentity("username", user.getUsername()),
                            Instant.now().plus(IDENTITY_HASH_RETENTION)
                    )
                    : checkpoint.requestId();

            if (user.getDeletedAt() == null && Boolean.TRUE.equals(user.getActive())) {
                user.setActive(false);
                userRepository.saveAndFlush(user);
            }

            runPagedDeletionCleanup(user, requestId, queueAppleRevocation);
            repairDurableDeletionImpacts(userId);
            assertDeletionPartitionsDrained(userId);
            int queuedMedia = countMediaCleanupEvidence(userId);

            // Keep the user record retryable until both cross-store security
            // cleanup and the durable deletion checkpoint have succeeded.
            authTokenStore.revokeAllForUser(userId);
            if (!operationalStore.completeAccountDeletionRetention(userId, queuedMedia)) {
                throw new ChallengeSphereException(
                        "Could not complete the account deletion checkpoint",
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        logger
                );
            }
            if (user.getDeletedAt() == null) {
                anonymizeUser(user);
            }
            clearMediaCleanupEvidence(userId);

            safeInvalidateCaches(userId, List.of());
            logger.info(
                    "Account deletion completed for userId={} with {} media object(s) queued",
                    userId,
                    queuedMedia
            );
            return completedResponse(queuedMedia);
        } finally {
            operationalStore.releaseLease(lease);
        }
    }

    private User requireDeletionUser(String userId) throws ChallengeSphereException {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new ChallengeSphereException(
                        "User not found", HttpStatus.NOT_FOUND, logger
                ));
    }

    private int queueOwnedMediaReferences(
            String requestId,
            User user,
            Collection<Submission> submissions,
            Collection<Challenge> createdChallenges
    ) {
        String userId = user.getId();
        Set<String> mediaOwnerIds = new LinkedHashSet<>();
        mediaOwnerIds.add(userId);
        if (hasText(user.getLegacyId())) {
            mediaOwnerIds.add(user.getLegacyId());
        }
        Set<MediaReference> references = new LinkedHashSet<>();
        addMediaReferences(
                references, "PROFILE", userId, user.getProfilePicUrl(), mediaOwnerIds
        );
        submissions.forEach(submission -> addMediaReferences(
                references,
                "SUBMISSION",
                submission.getId(),
                submission.getMediaUrls(),
                mediaOwnerIds
        ));
        createdChallenges.stream()
                .filter(challenge -> Boolean.TRUE.equals(challenge.getIsPrivate()))
                .forEach(challenge -> addMediaReferences(
                        references,
                        "CHALLENGE",
                        challenge.getId(),
                        challenge.getCoverPhotoUrls(),
                        mediaOwnerIds
                ));

        references.forEach(reference -> operationalStore.enqueueAccountMediaCleanup(
                requestId,
                userId,
                reference.sourceType(),
                reference.sourceId(),
                reference.objectKey()
        ));
        return references.size();
    }

    private void addMediaReferences(
            Set<MediaReference> references,
            String sourceType,
            String sourceId,
            String rawReferences,
            Collection<String> mediaOwnerIds
    ) {
        if (rawReferences == null || rawReferences.isBlank()) {
            return;
        }
        for (String value : rawReferences.split(",")) {
            String objectKey = value.trim();
            if (isOwnedObjectKey(objectKey, mediaOwnerIds)) {
                references.add(new MediaReference(sourceType, sourceId, objectKey));
            }
        }
    }

    private void queueProviderTokenRevocations(Collection<AuthIdentity> identities) {
        identities.stream()
                .filter(identity -> "APPLE".equalsIgnoreCase(identity.getProvider()))
                .map(AuthIdentity::getEncryptedRefreshToken)
                .filter(AccountDeletionService::hasText)
                .forEach(operationalStore::enqueueAppleTokenRevocation);
    }

    private boolean isOwnedObjectKey(String objectKey, Collection<String> mediaOwnerIds) {
        if (!hasText(objectKey)) {
            return false;
        }
        return mediaOwnerIds.stream()
                .filter(AccountDeletionService::hasText)
                .anyMatch(ownerId -> objectKey.startsWith("profiles/user_" + ownerId + "/")
                        || objectKey.startsWith("submissions/user_" + ownerId + "/")
                        || objectKey.startsWith(
                                "challenges/user_" + ownerId + "/covers/"
                        ));
    }

    private void deactivateAndAnonymizeCreatedChallenges(Collection<Challenge> challenges) {
        for (Challenge challenge : challenges) {
            challengeRepository.mutateExisting(challenge.getId(), latest -> {
                if (Boolean.TRUE.equals(latest.getIsPrivate())) {
                    latest.setActive(false);
                }
                latest.setCreatorUsername("Deleted user");
                latest.setCreatorProfilePicUrl(null);
            });
        }
    }

    private void redactSafetyRecords(
            String userId,
            Collection<Submission> ownedSubmissions,
            Collection<Challenge> createdChallenges
    ) {
        for (UserReport report : userReportRepository.findByParticipantUserId(userId)) {
            if (report.getDetails() != null) {
                report.setDetails(null);
                userReportRepository.save(report);
            }
        }

        LinkedHashMap<String, Report> contentReports = new LinkedHashMap<>();
        reportRepository.findByUserId(userId).stream()
                .filter(AccountDeletionService::isContentReport)
                .forEach(report -> contentReports.put(report.getId(), report));
        ownedSubmissions.forEach(submission -> reportRepository
                .findByTarget("SUBMISSION", submission.getId())
                .forEach(report -> contentReports.put(report.getId(), report)));
        commentRepository.findByUserId(userId).forEach(comment -> reportRepository
                .findByTarget("COMMENT", comment.getId())
                .forEach(report -> contentReports.put(report.getId(), report)));
        createdChallenges.forEach(challenge -> reportRepository
                .findByTarget("CHALLENGE", challenge.getId())
                .forEach(report -> contentReports.put(report.getId(), report)));
        for (Report report : contentReports.values()) {
            if (report.getDescription() != null) {
                report.setDescription(null);
                reportRepository.save(report);
            }
        }

        for (Notification notification : notificationRepository.findByActorUserId(userId)) {
            notification.setMessage(DELETED_ACTIVITY_MESSAGE);
            notification.setActionObject(null);
            notificationRepository.save(notification);
        }
    }

    private CleanupImpact deleteAssociatedData(String userId, List<Submission> ownedSubmissions) {
        Set<String> ownedSubmissionIds = ids(ownedSubmissions);
        Set<String> affectedSubmissionIds = new LinkedHashSet<>();
        Set<String> affectedUserIds = new LinkedHashSet<>();

        List<Comment> userComments = commentRepository.findByUserId(userId);
        userComments.stream()
                .map(Comment::getSubmissionId)
                .filter(Objects::nonNull)
                .filter(id -> !ownedSubmissionIds.contains(id))
                .forEach(affectedSubmissionIds::add);

        List<Glory> userGlories = gloryRepository.findByUserId(userId);
        userGlories.stream()
                .map(Glory::getSubmissionId)
                .filter(Objects::nonNull)
                .filter(id -> !ownedSubmissionIds.contains(id))
                .forEach(affectedSubmissionIds::add);

        for (Submission submission : ownedSubmissions) {
            commentRepository.deleteAll(commentRepository.findBySubmissionId(submission.getId()));
            gloryRepository.deleteAll(gloryRepository.findBySubmissionId(submission.getId()));
            reportRepository.findBySubmissionId(submission.getId()).stream()
                    .filter(report -> !isContentReport(report))
                    .forEach(reportRepository::delete);
        }
        deleteDistinct(userComments, Comment::getId, commentRepository::delete);
        deleteDistinct(userGlories, Glory::getId, gloryRepository::delete);
        reportRepository.findByUserId(userId).stream()
                .filter(report -> !isContentReport(report))
                .forEach(reportRepository::delete);

        List<Follow> follows = new ArrayList<>();
        follows.addAll(followRepository.findByFollowerId(userId));
        follows.addAll(followRepository.findByFollowingId(userId));
        follows.stream()
                .map(follow -> userId.equals(follow.getFollowerId())
                        ? follow.getFollowingId()
                        : follow.getFollowerId())
                .filter(Objects::nonNull)
                .forEach(affectedUserIds::add);
        deleteDistinct(follows, Follow::getId, followRepository::delete);

        List<UserBlock> blocks = new ArrayList<>();
        blocks.addAll(userBlockRepository.findByBlockerUserId(userId));
        blocks.addAll(userBlockRepository.findByBlockedUserId(userId));
        deleteDistinct(blocks, UserBlock::getId, userBlockRepository::delete);

        peerNudgeRepository.deleteAll(peerNudgeRepository.findByParticipantUserId(userId));
        peerReminderRepository.deleteAll(peerReminderRepository.findByUserId(userId));
        crewInviteRepository.deleteAll(crewInviteRepository.findByParticipantUserId(userId));
        for (ChallengeCrewLifecycle lifecycle : crewLifecycleRepository.findByStartedByUserId(userId)) {
            lifecycle.setStartedByUserId(null);
            crewLifecycleRepository.save(lifecycle);
        }

        inviteUsageRepository.deleteAll(inviteUsageRepository.findByUserId(userId));
        challengeInviteRepository.findByCreatedBy(userId).forEach(invite -> {
            inviteUsageRepository.deleteAll(inviteUsageRepository.findByInviteId(invite.getId()));
            challengeInviteRepository.delete(invite);
        });
        leaderboardEntryRepository.deleteAll(leaderboardEntryRepository.findByUserId(userId));

        ownedSubmissions.forEach(submissionRepository::delete);
        userChallengeRepository.deleteAll(userChallengeRepository.findByUserId(userId));
        notificationRepository.deleteByUserId(userId);
        pushDeviceTokenRepository.deleteAll(pushDeviceTokenRepository.findByUserId(userId));
        userPreferencesRepository.findByUserId(userId).ifPresent(userPreferencesRepository::delete);
        featureRequestRepository.deleteAll(featureRequestRepository.findByUserId(userId));
        userSubscriptionRepository.findById(userId).ifPresent(userSubscriptionRepository::delete);
        authIdentityRepository.deleteAll(authIdentityRepository.findByUserId(userId));

        return new CleanupImpact(
                Set.copyOf(affectedSubmissionIds),
                Set.copyOf(affectedUserIds)
        );
    }

    private void repairAggregateCounts(CleanupImpact impact) {
        for (String submissionId : impact.submissionIds()) {
            Submission submission = submissionRepository.findById(submissionId).orElse(null);
            if (submission == null) {
                continue;
            }
            long commentsCount = commentRepository.countActiveCommentsBySubmissionId(submissionId);
            long gloriesCount = gloryRepository.countActiveBySubmissionId(submissionId);
            submissionRepository.mutateExisting(submissionId, latest -> {
                latest.setCommentsCount(commentsCount);
                latest.setGloriesCount(gloriesCount);
            });
        }

        for (String affectedUserId : impact.userIds()) {
            User affected = userRepository.findById(affectedUserId).orElse(null);
            if (affected == null) {
                continue;
            }
            affected.setFollowers(Math.toIntExact(
                    followRepository.countActiveFollowersByFollowingId(affectedUserId)
            ));
            affected.setFollowing(Math.toIntExact(
                    followRepository.countActiveFollowingByFollowerId(affectedUserId)
            ));
            userRepository.save(affected);
        }
    }

    private void anonymizeUser(User user) {
        String userId = user.getId();
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
        user.setSubmissionsCount(0);
        user.setActive(false);
        user.setDeletedAt(Timestamp.from(Instant.now()));
        userRepository.saveAndFlush(user);
    }

    private String pseudonymizeIdentity(String field, String rawValue) {
        if (!hasText(rawValue)) {
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
            throw new IllegalStateException(
                    "Unable to pseudonymize deleted account identity", exception
            );
        }
    }

    private void safeInvalidateCaches(String userId, Collection<Challenge> createdChallenges) {
        try {
            createdChallenges.stream()
                    .map(Challenge::getId)
                    .filter(Objects::nonNull)
                    .forEach(cacheService::clearChallengeCache);
            cacheService.invalidateAllUserCaches(userId);
            cacheService.invalidateAllFeeds();
            cacheService.invalidateRecentAndTrendingChallenges();
        } catch (Exception exception) {
            logger.warn(
                    "Account deletion cache invalidation failed for userId={}: {}",
                    userId,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private AccountDeletionResponse completedResponse(int queuedMedia) {
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

    private static Set<String> ids(Collection<Submission> submissions) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        submissions.stream().map(Submission::getId).filter(Objects::nonNull).forEach(ids::add);
        return Set.copyOf(ids);
    }

    private static <T> void deleteDistinct(
            Collection<T> values,
            java.util.function.Function<T, String> id,
            java.util.function.Consumer<T> delete
    ) {
        LinkedHashMap<String, T> distinct = new LinkedHashMap<>();
        values.forEach(value -> distinct.putIfAbsent(id.apply(value), value));
        distinct.values().forEach(delete);
    }

    private static boolean isContentReport(Report report) {
        return hasText(report.getTargetType()) && hasText(report.getTargetId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record MediaReference(String sourceType, String sourceId, String objectKey) { }

    private record CleanupImpact(Set<String> submissionIds, Set<String> userIds) { }
}
