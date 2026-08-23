package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.response.ChallengeInviteDetailsResponse;
import com.ironside.challengesphere.api.response.CursorPageResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.persistence.dynamodb.ephemeral.EphemeralStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
import com.ironside.challengesphere.persistence.dynamodb.foundation.DynamoDbCursorCodec;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.ChallengeCrewInviteRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.PushNotificationService;
import com.ironside.challengesphere.util.NotificationType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final Logger logger = LogManager.getLogger(NotificationServiceImpl.class);

    private static final int PUSH_NOTIFICATIONS_BIT = 1;
    private static final String TYPE_FOLLOW = "FOLLOW";
    private static final String TYPE_SUBMISSION = "SUBMISSION";
    private static final String TYPE_GLORY = "GLORY";
    private static final String TYPE_COMMENT = "COMMENT";
    private static final String TYPE_CHALLENGE_INVITE = "CHALLENGE_INVITE";
    private static final String TYPE_CHALLENGE_UPDATE = "CHALLENGE_UPDATE";
    private static final String TYPE_CHALLENGE_REMOVAL = "CHALLENGE_REMOVAL";
    private static final String TYPE_MOMENTUM_MILESTONE = "MOMENTUM_MILESTONE";
    private static final Set<String> IMMEDIATE_PUSH_TYPES = Set.of(
            TYPE_FOLLOW, TYPE_GLORY, TYPE_COMMENT, TYPE_CHALLENGE_INVITE, TYPE_CHALLENGE_REMOVAL);
    private static final int MAX_CHALLENGE_INVITES_PER_REQUEST = 20;
    private static final int MAX_CHALLENGE_INVITES_PER_UTC_DAY = 50;
    private static final int MAX_NOTIFICATION_LIMIT = 100;
    private static final long INVITE_DELIVERY_RESERVED = 1L;

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ChallengeRepository challengeRepository;
    private final FollowRepository followRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final PushNotificationService pushNotificationService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MediaUrlService mediaUrlService;

    @Autowired
    private EphemeralStore ephemeralStore;

    @Autowired
    private MotivationMilestoneService motivationMilestoneService;

    @Autowired
    private ChallengeCrewInviteRepository challengeCrewInviteRepository;

    @Autowired(required = false)
    private RedisCacheServiceImpl cacheService;

    @Autowired
    private DynamoDbCursorCodec cursorCodec;

    private record PushDispatch(String userId, String type, String message, String actionObject) {
    }

    private record ChallengeInviteReservation(
            long status,
            String actorUserId,
            String quotaKey,
            String dedupeKey,
            String member,
            Instant expiresAt
    ) {
    }

    public NotificationServiceImpl(NotificationRepository notificationRepository,
                                   UserRepository userRepository,
                                   ChallengeRepository challengeRepository,
                                   FollowRepository followRepository,
                                   UserBlockRepository userBlockRepository,
                                   UserPreferencesRepository preferencesRepository,
                                   PushNotificationService pushNotificationService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.challengeRepository = challengeRepository;
        this.followRepository = followRepository;
        this.userBlockRepository = userBlockRepository;
        this.preferencesRepository = preferencesRepository;
        this.pushNotificationService = pushNotificationService;
    }

    @Override
    @Transactional(readOnly = true)
    public CursorPageResponse<Notification> getNotifications(String userId, int limit, String cursor)
            throws ChallengeSphereException {
        if (userId == null || userId.isBlank()) {
            throw new ChallengeSphereException("User ID is required", HttpStatus.BAD_REQUEST, logger);
        }
        int boundedLimit = Math.max(1, Math.min(limit, MAX_NOTIFICATION_LIMIT));
        String queryBinding = "notifications:v1|user=" + userId + "|limit=" + boundedLimit;
        Map<String, AttributeValue> exclusiveStartKey = decodeCursor(cursor, queryBinding);
        DynamoPage<Notification> notifications = notificationRepository.findPageByUserIdOrderBySentAtDesc(
                userId,
                boundedLimit,
                exclusiveStartKey
        );
        boolean hasMore = !notifications.lastEvaluatedKey().isEmpty();
        return CursorPageResponse.of(
                notifications.items(),
                encodeCursor(queryBinding, notifications.lastEvaluatedKey()),
                hasMore
        );
    }

    private Map<String, AttributeValue> decodeCursor(String cursor, String queryBinding)
            throws ChallengeSphereException {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }
        try {
            return cursorCodec.decode(cursor, queryBinding).lastEvaluatedKey();
        } catch (IllegalArgumentException exception) {
            throw new ChallengeSphereException("Invalid cursor", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private String encodeCursor(
            String queryBinding,
            Map<String, AttributeValue> lastEvaluatedKey
    ) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        return cursorCodec.encode(
                queryBinding,
                new DynamoDbCursorCodec.CursorState(lastEvaluatedKey)
        );
    }

    @Override
    @Transactional
    public Notification createNotification(String userId, String type, String message, String actionObject) {
        Notification saved = notificationRepository.save(buildNotification(userId, type, message, actionObject));
        invalidateUnreadCount(userId);
        logger.info("Created notification id={} for userId={} type={}", saved.getId(), userId, type);
        return saved;
    }

    @Override
    @Transactional
    public Notification notifyChallengeUpdate(String recipientUserId, String message, String actionObject) {
        return userRepository.findById(recipientUserId)
                .map(recipient -> deliver(
                        recipient,
                        preferencesRepository.findByUserId(recipientUserId).orElse(null),
                        TYPE_CHALLENGE_UPDATE,
                        message,
                        actionObject
                ))
                .orElse(null);
    }

    @Override
    @Transactional
    public Notification notifyParticipantRemoved(
            Challenge challenge,
            String recipientUserId,
            String actorUserId,
            String reason) {
        if (challenge == null || recipientUserId == null || reason == null || reason.isBlank()) {
            return null;
        }

        String challengeTitle = safe(challenge.getTitle());
        String challengeLabel = challengeTitle.isBlank() ? "this challenge" : "\"" + challengeTitle + "\"";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("challengeId", challenge.getId());
        data.put("challengeTitle", challengeTitle);
        data.put("actorUserId", actorUserId);
        data.put("removalReason", reason);

        return userRepository.findById(recipientUserId)
                .map(recipient -> deliver(
                        recipient,
                        preferencesRepository.findByUserId(recipientUserId).orElse(null),
                        TYPE_CHALLENGE_REMOVAL,
                        "You were removed from " + challengeLabel + ". Reason: " + reason,
                        toJson(data)
                ))
                .orElse(null);
    }

    @Override
    @Transactional
    public Notification notifyFollow(String recipientUserId, String actorUserId) {
        if (Objects.equals(recipientUserId, actorUserId)) {
            return null;
        }

        Optional<User> recipient = userRepository.findById(recipientUserId);
        Optional<User> actor = userRepository.findById(actorUserId);
        if (recipient.isEmpty() || actor.isEmpty()) {
            logger.warn("Skipping follow notification; recipient={} actor={} found? {}/{}",
                    recipientUserId, actorUserId, recipient.isPresent(), actor.isPresent());
            return null;
        }
        if (userBlockRepository.existsActiveBlockBetween(recipientUserId, actorUserId)) {
            return null;
        }

        String actorName = displayName(actor.get());
        Map<String, Object> data = baseActorPayload(actor.get());
        data.put("targetUserId", recipientUserId);

        return deliver(recipient.get(),
                preferencesRepository.findByUserId(recipientUserId).orElse(null),
                TYPE_FOLLOW,
                actorName + " started following you",
                toJson(data));
    }

    @Override
    @Async("asyncExecutor")
    @Transactional
    public void notifyFollowAsync(String recipientUserId, String actorUserId) {
        try {
            notifyFollow(recipientUserId, actorUserId);
        } catch (Exception exception) {
            logger.warn("Failed to create follow notification for recipient={} actor={}: {}",
                    recipientUserId, actorUserId, exception.getClass().getSimpleName());
        }
    }

    @Override
    @Async("asyncExecutor")
    public void notifyMomentumMilestonesAfterSubmissionAsync(
            String userId,
            String challengeId,
            LocalDate currentDate,
            boolean evaluateRank
    ) {
        try {
            motivationMilestoneService.evaluateAfterSubmission(
                    userId, challengeId, currentDate, evaluateRank);
        } catch (RuntimeException exception) {
            logger.warn("Failed to create momentum milestones for userId={} challengeId={}: {}",
                    userId, challengeId, exception.getClass().getSimpleName());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int notifySubmission(Submission submission, Challenge challenge, Collection<String> recipientUserIds) {
        if (submission == null || challenge == null || recipientUserIds == null || recipientUserIds.isEmpty()) {
            return 0;
        }

        String authorUserId = submission.getUserId();
        Set<String> normalizedRecipients = recipientUserIds.stream()
                .filter(Objects::nonNull)
                .filter(userId -> !Objects.equals(userId, authorUserId))
                .collect(Collectors.toSet());
        if (normalizedRecipients.isEmpty()) {
            return 0;
        }

        User author = userRepository.findById(authorUserId).orElse(null);
        if (author == null) {
            logger.warn("Skipping submission notifications; author userId={} not found", authorUserId);
            return 0;
        }

        Map<String, UserPreferences> prefsByUserId = preferencesRepository.findByUserIdIn(normalizedRecipients).stream()
                .collect(Collectors.toMap(UserPreferences::getUserId, prefs -> prefs));
        List<User> recipients = userRepository.findAllById(normalizedRecipients);
        List<Notification> toSave = new ArrayList<>();
        List<PushDispatch> pushes = new ArrayList<>();

        String challengeTitle = safe(challenge.getTitle());
        String message = displayName(author) + " posted Day " + submission.getDayNumber()
                + (challengeTitle.isBlank() ? "" : " in " + challengeTitle);
        String actionObject = toJson(baseSubmissionPayload(author, submission, challenge));

        for (User recipient : recipients) {
            if (userBlockRepository.existsActiveBlockBetween(recipient.getId(), authorUserId)) {
                continue;
            }
            UserPreferences prefs = prefsByUserId.get(recipient.getId());
            if (allowsInbox(recipient)) {
                toSave.add(buildNotification(recipient.getId(), TYPE_SUBMISSION, message, actionObject));
                if (allowsPush(recipient, prefs, TYPE_SUBMISSION)) {
                    pushes.add(new PushDispatch(recipient.getId(), TYPE_SUBMISSION, message, actionObject));
                }
            }
        }

        if (!toSave.isEmpty()) {
            notificationRepository.saveAll(toSave);
            toSave.stream().map(Notification::getUserId).distinct().forEach(this::invalidateUnreadCount);
            dispatchPushesAfterCommit(pushes);
        }
        logger.info("Created {} submission notification(s) for submissionId={}", toSave.size(), submission.getId());
        return toSave.size();
    }

    @Override
    @Async("asyncExecutor")
    @Transactional
    public void notifyChallengeInvitesAsync(Challenge challenge, String actorUserId, Collection<String> requestedRecipientUserIds) {
        List<ChallengeInviteReservation> deliveryReservations = new ArrayList<>();
        boolean notificationsPersisted = false;
        try {
            if (challenge == null || actorUserId == null || requestedRecipientUserIds == null || requestedRecipientUserIds.isEmpty())
                return;

            User actor = userRepository.findById(actorUserId).orElse(null);
            if (actor == null || !Boolean.TRUE.equals(actor.getActive())) {
                logger.warn("Skipping challenge invite notifications; actor userId={} not found", actorUserId);
                return;
            }
            boolean privateChallenge = Boolean.TRUE.equals(challenge.getIsPrivate());
            String ownerUserId = challenge.getCurrentOwnerId() != null
                    ? challenge.getCurrentOwnerId()
                    : challenge.getCreatorId();
            boolean challengeOwner = Objects.equals(actorUserId, ownerUserId);
            if (privateChallenge && !challengeOwner) {
                logger.warn(
                        "Skipped non-owner private challenge invitations for challengeId={} actorUserId={}",
                        challenge.getId(), actorUserId);
                return;
            }

            Set<String> requestedRecipients = requestedRecipientUserIds.stream()
                    .filter(Objects::nonNull)
                    .filter(userId -> !userId.isBlank())
                    .filter(userId -> !Objects.equals(userId, actorUserId))
                    .distinct()
                    .limit(MAX_CHALLENGE_INVITES_PER_REQUEST)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (requestedRecipients.isEmpty()) {
                return;
            }

            Set<String> socialGraphUserIds = new HashSet<>();
            socialGraphUserIds.addAll(followRepository.findActiveFollowedCandidateIds(
                    actorUserId,
                    requestedRecipients
            ));
            socialGraphUserIds.addAll(followRepository.findActiveFollowerCandidateIds(
                    actorUserId,
                    requestedRecipients
            ));
            requestedRecipients.retainAll(socialGraphUserIds);
            if (requestedRecipients.isEmpty()) {
                logger.info("Skipped challenge invite notifications outside actor userId={} social graph", actorUserId);
                return;
            }

            Map<String, UserPreferences> prefsByUserId = preferencesRepository.findByUserIdIn(requestedRecipients).stream()
                    .collect(Collectors.toMap(UserPreferences::getUserId, prefs -> prefs));
            List<User> recipients = userRepository.findAllById(requestedRecipients);
            List<Notification> toSave = new ArrayList<>();
            List<PushDispatch> pushes = new ArrayList<>();
            Set<String> pushRecipientIds = new HashSet<>();

            String challengeTitle = safe(challenge.getTitle());
            String visibility = privateChallenge ? "private" : "public";
            String message = displayName(actor) + " has invited you to join their " + visibility
                    + " challenge with name: "
                    + (challengeTitle.isBlank() ? "Untitled challenge" : challengeTitle);

            for (User recipient : recipients) {
                if (!Boolean.TRUE.equals(recipient.getActive())
                        || userBlockRepository.existsActiveBlockBetween(recipient.getId(), actorUserId)) {
                    continue;
                }
                UserPreferences prefs = prefsByUserId.get(recipient.getId());
                ChallengeInviteReservation reservation = reserveChallengeInviteDelivery(
                        actorUserId, challenge.getId(), recipient.getId());
                if (reservation.status() != INVITE_DELIVERY_RESERVED)
                    continue;
                deliveryReservations.add(reservation);

                String actionObject = toJson(baseChallengeInvitePayload(actor, recipient, challenge));
                if (allowsInbox(recipient)) {
                    Notification notification = buildNotification(
                            recipient.getId(), TYPE_CHALLENGE_INVITE, message, actionObject);
                    notification.setChallengeId(challenge.getId());
                    toSave.add(notification);
                    if (allowsPush(recipient, prefs, TYPE_CHALLENGE_INVITE)) {
                        pushRecipientIds.add(recipient.getId());
                    }
                }
            }

            if (!toSave.isEmpty()) {
                releaseChallengeInviteReservationsAfterRollback(deliveryReservations);
                notificationRepository.saveAll(toSave);
                for (Notification notification : toSave) {
                    if (pushRecipientIds.contains(notification.getUserId())) {
                        pushes.add(new PushDispatch(
                                notification.getUserId(),
                                TYPE_CHALLENGE_INVITE,
                                message,
                                withNotificationId(notification.getActionObject(), notification.getId())
                        ));
                    }
                }
                toSave.stream().map(Notification::getUserId).distinct().forEach(this::invalidateUnreadCount);
                notificationsPersisted = true;
                dispatchPushesAfterCommit(pushes);
            }
            logger.info("Created {} challenge invite notification(s) for challengeId={}", toSave.size(), challenge.getId());
        } catch (Exception e) {
            if (!notificationsPersisted) {
                releaseChallengeInviteReservations(deliveryReservations);
            }
            logger.warn("Failed to create challenge invite notifications for challengeId={}: {}",
                    challenge == null ? null : challenge.getId(), e.getClass().getSimpleName());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public ChallengeInviteDetailsResponse getChallengeInviteDetails(
            String notificationId,
            String recipientUserId
    ) throws ChallengeSphereException {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> inviteUnavailable());
        if (!Objects.equals(recipientUserId, notification.getUserId())
                || !TYPE_CHALLENGE_INVITE.equalsIgnoreCase(safe(notification.getType()))) {
            throw inviteUnavailable();
        }

        Map<String, Object> action = parseActionObject(notification.getActionObject());
        String challengeId = notification.getChallengeId() != null
                ? notification.getChallengeId()
                : idAsString(action.get("challengeId"));
        Challenge challenge = challengeId == null
                ? null
                : challengeRepository.findById(challengeId).orElse(null);
        if (challenge == null || !Boolean.TRUE.equals(challenge.getActive())) {
            throw inviteUnavailable();
        }

        String inviterUserId = idAsString(action.get("actorUserId"));
        if (inviterUserId != null
                && userBlockRepository.existsActiveBlockBetween(recipientUserId, inviterUserId)) {
            throw inviteUnavailable();
        }
        User inviter = inviterUserId == null ? null : userRepository.findById(inviterUserId).orElse(null);
        String creatorUserId = challenge.getCreatorId();
        if (creatorUserId != null
                && userBlockRepository.existsActiveBlockBetween(recipientUserId, creatorUserId)) {
            throw inviteUnavailable();
        }
        User creator = creatorUserId == null ? null : userRepository.findById(creatorUserId).orElse(null);
        ChallengeCreationContentPolicy.PreviewContent content =
                ChallengeCreationContentPolicy.parseStoredContent(
                        challenge.getDescription(), challenge.getBenefits());

        return ChallengeInviteDetailsResponse.builder()
                .notificationId(notification.getId())
                .challengeId(challenge.getId())
                .challengeTitle(challenge.getTitle())
                .challengeDescription(blankToNull(content.description()))
                .challengeRules(blankToNull(content.rules()))
                .challengeBenefits(blankToNull(content.benefits()))
                .challengeCategories(blankToNull(challenge.getCategories()))
                .challengeMode(blankToNull(challenge.getChallengeMode()))
                .proofMode(blankToNull(challenge.getProofMode()))
                .challengeStartDate(challenge.getStartDate())
                .challengeDurationDays(challenge.getNumberOfDays())
                .privateChallenge(Boolean.TRUE.equals(challenge.getIsPrivate()))
                .inviterUserId(inviterUserId)
                .inviterName(userName(inviter))
                .inviterUsername(userUsername(inviter))
                .creatorUserId(creatorUserId)
                .creatorName(userName(creator))
                .creatorUsername(userUsername(creator))
                .build();
    }

    @Override
    @Transactional
    public int invalidateChallengeInvitesForChallenge(String challengeId) {
        if (challengeId == null) {
            return 0;
        }
        int deletedNotifications = notificationRepository.deleteChallengeInvitesByChallengeId(challengeId);
        int deletedCrewInvites = challengeCrewInviteRepository.deleteAllByChallengeId(challengeId);
        return deletedNotifications + deletedCrewInvites;
    }

    @Override
    @Transactional
    public int invalidateChallengeInviteForRecipient(String challengeId, String recipientUserId) {
        if (challengeId == null || recipientUserId == null) {
            return 0;
        }
        int deletedNotifications = notificationRepository
                .deleteChallengeInviteByChallengeIdAndUserId(challengeId, recipientUserId);
        int deletedCrewInvites = challengeCrewInviteRepository
                .deleteByChallengeIdAndInvitedUserId(challengeId, recipientUserId);
        return deletedNotifications + deletedCrewInvites;
    }

    @Override
    @Transactional
    public int notifyChallengeJoined(Challenge challenge, String joiningUserId, Collection<String> recipientUserIds) {
        if (challenge == null || joiningUserId == null || recipientUserIds == null || recipientUserIds.isEmpty()) {
            return 0;
        }

        Set<String> normalizedRecipients = recipientUserIds.stream()
                .filter(Objects::nonNull)
                .filter(userId -> !userId.isBlank())
                .filter(userId -> !Objects.equals(userId, joiningUserId))
                .collect(Collectors.toSet());
        if (normalizedRecipients.isEmpty()) {
            return 0;
        }

        User joiner = userRepository.findById(joiningUserId).orElse(null);
        if (joiner == null) {
            logger.warn("Skipping challenge join notification; joining userId={} not found", joiningUserId);
            return 0;
        }

        Map<String, UserPreferences> prefsByUserId = preferencesRepository.findByUserIdIn(normalizedRecipients).stream()
                .collect(Collectors.toMap(UserPreferences::getUserId, prefs -> prefs));
        List<User> recipients = userRepository.findAllById(normalizedRecipients);
        List<Notification> toSave = new ArrayList<>();
        List<PushDispatch> pushes = new ArrayList<>();

        String challengeTitle = safe(challenge.getTitle());
        String message = displayName(joiner) + " joined"
                + (challengeTitle.isBlank() ? " your challenge" : " " + challengeTitle);

        for (User recipient : recipients) {
            if (userBlockRepository.existsActiveBlockBetween(recipient.getId(), joiningUserId)) {
                continue;
            }
            UserPreferences prefs = prefsByUserId.get(recipient.getId());
            String actionObject = toJson(baseChallengeJoinedPayload(joiner, recipient, challenge));
            if (allowsInbox(recipient)) {
                toSave.add(buildNotification(recipient.getId(), TYPE_CHALLENGE_UPDATE, message, actionObject));
                if (allowsPush(recipient, prefs, TYPE_CHALLENGE_UPDATE)) {
                    pushes.add(new PushDispatch(recipient.getId(), TYPE_CHALLENGE_UPDATE, message, actionObject));
                }
            }
        }

        if (!toSave.isEmpty()) {
            notificationRepository.saveAll(toSave);
            toSave.stream().map(Notification::getUserId).distinct().forEach(this::invalidateUnreadCount);
            dispatchPushesAfterCommit(pushes);
        }
        logger.info("Created {} challenge join notification(s) for challengeId={}", toSave.size(), challenge.getId());
        return toSave.size();
    }

    @Override
    @Async("asyncExecutor")
    @Transactional
    public void notifyChallengeJoinedAsync(
            Challenge challenge,
            String joiningUserId,
            Collection<String> recipientUserIds
    ) {
        try {
            notifyChallengeJoined(challenge, joiningUserId, recipientUserIds);
        } catch (Exception exception) {
            logger.warn("Failed to create challenge join notifications for challengeId={} joiningUserId={}: {}",
                    challenge == null ? null : challenge.getId(),
                    joiningUserId,
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    @Transactional
    public Notification notifyGlory(Submission submission, String actorUserId) {
        if (submission == null || Objects.equals(submission.getUserId(), actorUserId)) {
            return null;
        }
        return notifySubmissionOwner(submission, actorUserId, null, TYPE_GLORY, "gloried your submission");
    }

    @Override
    @Async("asyncExecutor")
    @Transactional
    public void notifyGloryAsync(Submission submission, String actorUserId) {
        try {
            notifyGlory(submission, actorUserId);
        } catch (Exception exception) {
            logger.warn("Failed to create glory notification for submissionId={} actorUserId={}: {}",
                    submission == null ? null : submission.getId(),
                    actorUserId,
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    @Transactional
    public Notification notifyComment(Submission submission, String actorUserId, String commentId) {
        if (submission == null || Objects.equals(submission.getUserId(), actorUserId)) {
            return null;
        }
        return notifySubmissionOwner(submission, actorUserId, commentId, TYPE_COMMENT, "commented on your submission");
    }

    @Override
    @Async("asyncExecutor")
    @Transactional
    public void notifyCommentAsync(Submission submission, String actorUserId, String commentId) {
        try {
            notifyComment(submission, actorUserId, commentId);
        } catch (Exception e) {
            logger.warn("Failed to create comment notification for commentId={}: {}", commentId, e.getClass().getSimpleName());
        }
    }

    private Notification notifySubmissionOwner(Submission submission,
                                               String actorUserId,
                                               String commentId,
                                               String type,
                                               String actionText) {
        Optional<User> recipient = userRepository.findById(submission.getUserId());
        Optional<User> actor = userRepository.findById(actorUserId);
        if (recipient.isEmpty() || actor.isEmpty()) {
            logger.warn("Skipping {} notification; recipient={} actor={} found? {}/{}",
                    type, submission.getUserId(), actorUserId, recipient.isPresent(), actor.isPresent());
            return null;
        }
        if (userBlockRepository.existsActiveBlockBetween(submission.getUserId(), actorUserId)) {
            return null;
        }

        Map<String, Object> data = baseActorPayload(actor.get());
        data.put("submissionId", submission.getId());
        data.put("challengeId", submission.getChallengeId());
        challengeRepository.findById(submission.getChallengeId())
                .map(Challenge::getTitle)
                .ifPresent(title -> data.put("challengeTitle", title));
        data.put("targetUserId", submission.getUserId());
        data.put("targetUsername", recipient.get().getUsername());
        data.put("targetProfilePicUrl", readableProfilePic(
                recipient.get().getId(), recipient.get().getProfilePicUrl()));
        data.put("dayNumber", submission.getDayNumber());
        data.put("mediaUrl", firstMediaUrl(submission.getMediaUrls()));
        if (commentId != null) {
            data.put("commentId", commentId);
        }

        return deliver(recipient.get(),
                preferencesRepository.findByUserId(recipient.get().getId()).orElse(null),
                type,
                displayName(actor.get()) + " " + actionText,
                toJson(data));
    }

    private Notification deliver(User recipient,
                                 UserPreferences prefs,
                                 String type,
                                 String message,
                                 String actionObject) {
        Notification saved = null;
        if (allowsInbox(recipient)) {
            saved = notificationRepository.save(buildNotification(recipient.getId(), type, message, actionObject));
            invalidateUnreadCount(recipient.getId());
            if (allowsPush(recipient, prefs, type)) {
                dispatchPushAfterCommit(recipient.getId(), type, message, actionObject);
            }
        }
        return saved;
    }

    private void invalidateUnreadCount(String userId) {
        if (cacheService != null && userId != null) {
            runAfterCommit(() -> cacheService.delete("notifications:unread:" + userId));
        }
    }

    private void dispatchPushesAfterCommit(List<PushDispatch> pushes) {
        if (pushes == null || pushes.isEmpty()) {
            return;
        }
        Runnable dispatch = () -> pushes.forEach(push ->
                pushNotificationService.sendInteractionPush(push.userId(), push.type(), push.message(), push.actionObject()));
        runAfterCommit(dispatch);
    }

    private void dispatchPushAfterCommit(String userId, String type, String message, String actionObject) {
        runAfterCommit(() -> pushNotificationService.sendInteractionPush(userId, type, message, actionObject));
    }

    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    private Notification buildNotification(String userId, String type, String message, String actionObject) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setMessage(message);
        n.setActionObject(actionObject);
        n.setSentAt(Timestamp.from(Instant.now()));
        return n;
    }

    private boolean allowsInbox(User user) {
        return user != null
                && user.getId() != null
                && !Boolean.FALSE.equals(user.getActive());
    }

    private boolean allowsPush(User user, UserPreferences prefs, String type) {
        if (!allowsInbox(user)) {
            return false;
        }
        String normalizedType = safe(type).toUpperCase();
        if (!NotificationType.supportsImmediatePush(normalizedType)) {
            return false;
        }
        Integer mask = user.getNotifications();
        boolean legacyPushEnabled = mask == null || (mask & PUSH_NOTIFICATIONS_BIT) == PUSH_NOTIFICATIONS_BIT;
        return legacyPushEnabled
                && (prefs == null || Boolean.TRUE.equals(prefs.getPushNotificationsEnabled()))
                && (!TYPE_CHALLENGE_INVITE.equals(normalizedType) || allowsChallengeInvites(prefs))
                && allowsType(prefs, normalizedType);
    }

    private boolean allowsChallengeInvites(UserPreferences prefs) {
        return prefs == null || Boolean.TRUE.equals(prefs.getAllowChallengeInvites());
    }

    private boolean allowsType(UserPreferences prefs, String type) {
        if (prefs == null) {
            return true;
        }
        return switch (safe(type).toUpperCase()) {
            case TYPE_FOLLOW -> Boolean.TRUE.equals(prefs.getNotifyOnFollow());
            case TYPE_GLORY -> Boolean.TRUE.equals(prefs.getNotifyOnGlory());
            case TYPE_COMMENT -> Boolean.TRUE.equals(prefs.getNotifyOnComment());
            case "INVITE", TYPE_CHALLENGE_INVITE -> Boolean.TRUE.equals(prefs.getNotifyOnChallengeInvite());
            case TYPE_SUBMISSION, "SUBMISSION_UPDATE", "CHALLENGE_UPDATE" -> Boolean.TRUE.equals(prefs.getNotifyOnChallengeUpdate());
            case TYPE_MOMENTUM_MILESTONE -> Boolean.TRUE.equals(prefs.getNotifyOnMotivation());
            default -> true;
        };
    }

    private Map<String, Object> baseActorPayload(User actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actorUserId", actor.getId());
        data.put("actorUsername", actor.getUsername());
        data.put("actorProfilePicUrl", readableProfilePic(actor.getId(), actor.getProfilePicUrl()));
        return data;
    }

    private Map<String, Object> baseSubmissionPayload(User author, Submission submission, Challenge challenge) {
        Map<String, Object> data = baseActorPayload(author);
        data.put("submissionId", submission.getId());
        data.put("challengeId", challenge.getId());
        data.put("challengeTitle", challenge.getTitle());
        data.put("dayNumber", submission.getDayNumber());
        data.put("mediaUrl", firstMediaUrl(submission.getMediaUrls()));
        return data;
    }

    private Map<String, Object> baseChallengeInvitePayload(User actor, User recipient, Challenge challenge) {
        Map<String, Object> data = baseActorPayload(actor);
        data.put("actionType", TYPE_CHALLENGE_INVITE);
        data.put("status", "PENDING");
        data.put("challengeId", challenge.getId());
        data.put("challengeTitle", challenge.getTitle());
        data.put("creatorUserId", challenge.getCreatorId());
        data.put("targetUserId", recipient.getId());
        data.put("targetUsername", recipient.getUsername());
        data.put("targetProfilePicUrl", readableProfilePic(
                recipient.getId(), recipient.getProfilePicUrl()));
        data.put("isPrivate", challenge.getIsPrivate());
        return data;
    }

    private ChallengeInviteReservation reserveChallengeInviteDelivery(
            String actorUserId,
            String challengeId,
            String recipientUserId
    ) {
        if (actorUserId == null || challengeId == null || recipientUserId == null) {
            return new ChallengeInviteReservation(0L, "", "", "", "", Instant.EPOCH);
        }

        Instant now = Instant.now();
        LocalDate utcDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        String quotaKey = "notification-quota:challenge-invite:{" + actorUserId + "}:" + utcDate;
        String dedupeKey = "notification-dedupe:challenge-invite:{" + actorUserId + "}:"
                + challengeId + ":" + recipientUserId;
        String member = challengeId + ':' + recipientUserId;
        Instant expiresAt = now.plus(Duration.ofHours(24));
        boolean reserved = ephemeralStore.reserveNotificationQuota(
                actorUserId,
                quotaKey,
                dedupeKey,
                member,
                true,
                MAX_CHALLENGE_INVITES_PER_UTC_DAY,
                expiresAt,
                now
        );
        return new ChallengeInviteReservation(
                reserved ? INVITE_DELIVERY_RESERVED : 0L,
                actorUserId,
                quotaKey,
                dedupeKey,
                member,
                expiresAt
        );
    }

    private void releaseChallengeInviteReservationsAfterRollback(
            List<ChallengeInviteReservation> reservations
    ) {
        if (reservations.isEmpty() || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    releaseChallengeInviteReservations(reservations);
                }
            }
        });
    }

    private void releaseChallengeInviteReservations(List<ChallengeInviteReservation> reservations) {
        for (ChallengeInviteReservation reservation : reservations) {
            try {
                ephemeralStore.releaseNotificationQuota(
                        reservation.actorUserId(),
                        reservation.quotaKey(),
                        reservation.dedupeKey(),
                        reservation.member(),
                        true,
                        Instant.now()
                );
            } catch (RuntimeException exception) {
                logger.warn("Failed to release challenge invite reservation: {}",
                        exception.getClass().getSimpleName());
            }
        }
    }

    private Map<String, Object> baseChallengeJoinedPayload(User actor, User recipient, Challenge challenge) {
        Map<String, Object> data = baseActorPayload(actor);
        data.put("actionType", "JOINED");
        data.put("challengeId", challenge.getId());
        data.put("challengeTitle", challenge.getTitle());
        data.put("creatorUserId", challenge.getCreatorId());
        data.put("targetUserId", recipient.getId());
        data.put("targetUsername", recipient.getUsername());
        data.put("targetProfilePicUrl", readableProfilePic(
                recipient.getId(), recipient.getProfilePicUrl()));
        data.put("isPrivate", challenge.getIsPrivate());
        return data;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize notification action object: {}", e.getClass().getSimpleName());
            return "{}";
        }
    }

    private String withNotificationId(String actionObject, String notificationId) {
        Map<String, Object> data = parseActionObject(actionObject);
        data.put("notificationId", notificationId);
        return toJson(data);
    }

    private Map<String, Object> parseActionObject(String actionObject) {
        if (safe(actionObject).isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                    actionObject,
                    new TypeReference<LinkedHashMap<String, Object>>() {});
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String idAsString(Object value) {
        if (value == null) {
            return null;
        }
        return blankToNull(value.toString());
    }

    private String userName(User user) {
        if (user == null) {
            return null;
        }
        String name = blankToNull(user.getName());
        return name == null ? blankToNull(user.getUsername()) : name;
    }

    private String userUsername(User user) {
        return user == null ? null : blankToNull(user.getUsername());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private ChallengeSphereException inviteUnavailable() {
        return new ChallengeSphereException(
                "Challenge invitation is no longer available",
                HttpStatus.NOT_FOUND,
                logger
        );
    }

    private String firstMediaUrl(String mediaUrls) {
        if (mediaUrls == null || mediaUrls.isBlank()) {
            return null;
        }
        String[] parts = mediaUrls.split(",");
        return parts.length == 0 ? null : parts[0].trim();
    }

    private String readableProfilePic(String userId, String value) {
        return value == null || value.isBlank() || mediaUrlService == null
                ? value
                : mediaUrlService.toReadableProfilePhoto(userId, value);
    }

    private String displayName(User user) {
        if (user == null) {
            return "Someone";
        }
        return user.getUsername() == null || user.getUsername().isBlank()
                ? "Someone"
                : "@" + user.getUsername();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
