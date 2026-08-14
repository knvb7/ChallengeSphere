package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.FollowRepository;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.UserBlockRepository;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.NotificationService;
import com.ironside.challengesphere.service.PushNotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
    private static final long INVITE_DELIVERY_RESERVED = 1L;
    private static final long INVITE_DELIVERY_QUOTA_EXHAUSTED = -1L;
    private static final DefaultRedisScript<Long> RESERVE_CHALLENGE_INVITE_SCRIPT;
    private static final DefaultRedisScript<Long> RELEASE_CHALLENGE_INVITE_SCRIPT;

    static {
        RESERVE_CHALLENGE_INVITE_SCRIPT = new DefaultRedisScript<>();
        RESERVE_CHALLENGE_INVITE_SCRIPT.setResultType(Long.class);
        RESERVE_CHALLENGE_INVITE_SCRIPT.setScriptText("""
                if redis.call('EXISTS', KEYS[2]) == 1 then
                    return 0
                end
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                local maxInvites = tonumber(ARGV[1])
                if current >= maxInvites then
                    return -1
                end
                local updated = redis.call('INCR', KEYS[1])
                local ttl = redis.call('PTTL', KEYS[1])
                if updated == 1 or ttl < 0 then
                    redis.call('PEXPIRE', KEYS[1], ARGV[2])
                end
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[3])
                return 1
                """);

        RELEASE_CHALLENGE_INVITE_SCRIPT = new DefaultRedisScript<>();
        RELEASE_CHALLENGE_INVITE_SCRIPT.setResultType(Long.class);
        RELEASE_CHALLENGE_INVITE_SCRIPT.setScriptText("""
                if redis.call('DEL', KEYS[2]) == 0 then
                    return 0
                end
                local current = tonumber(redis.call('GET', KEYS[1]) or '0')
                if current <= 1 then
                    redis.call('DEL', KEYS[1])
                else
                    redis.call('DECR', KEYS[1])
                end
                return 1
                """);
    }

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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MotivationMilestoneService motivationMilestoneService;

    @Autowired(required = false)
    private RedisCacheServiceImpl cacheService;

    private record PushDispatch(Long userId, String type, String message, String actionObject) {
    }

    private record ChallengeInviteReservation(long status, String quotaKey, String dedupeKey) {
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
    @Transactional
    public Notification createNotification(Long userId, String type, String message, String actionObject) {
        Notification saved = notificationRepository.save(buildNotification(userId, type, message, actionObject));
        invalidateUnreadCount(userId);
        logger.info("Created notification id={} for userId={} type={}", saved.getId(), userId, type);
        return saved;
    }

    @Override
    @Transactional
    public Notification notifyChallengeUpdate(Long recipientUserId, String message, String actionObject) {
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
            Long recipientUserId,
            Long actorUserId,
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
    public Notification notifyFollow(Long recipientUserId, Long actorUserId) {
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
    public void notifyFollowAsync(Long recipientUserId, Long actorUserId) {
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
            Long userId,
            Long challengeId,
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
    public int notifySubmission(Submission submission, Challenge challenge, Collection<Long> recipientUserIds) {
        if (submission == null || challenge == null || recipientUserIds == null || recipientUserIds.isEmpty()) {
            return 0;
        }
        if (!Boolean.TRUE.equals(challenge.getIsPrivate()) && Boolean.TRUE.equals(submission.getIsPrivate())) {
            return 0;
        }

        Long authorUserId = submission.getUserId();
        Set<Long> normalizedRecipients = recipientUserIds.stream()
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

        Map<Long, UserPreferences> prefsByUserId = preferencesRepository.findByUserIdIn(normalizedRecipients).stream()
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
    public void notifyChallengeInvitesAsync(Challenge challenge, Long actorUserId, Collection<Long> requestedRecipientUserIds) {
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

            Set<Long> requestedRecipients = requestedRecipientUserIds.stream()
                    .filter(Objects::nonNull)
                    .filter(userId -> userId > 0)
                    .filter(userId -> !Objects.equals(userId, actorUserId))
                    .distinct()
                    .limit(MAX_CHALLENGE_INVITES_PER_REQUEST)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (requestedRecipients.isEmpty()) {
                return;
            }

            Set<Long> socialGraphUserIds = new HashSet<>();
            followRepository.findActiveFollowsByFollowerId(actorUserId).stream()
                    .map(follow -> follow.getFollowingId())
                    .filter(Objects::nonNull)
                    .forEach(socialGraphUserIds::add);
            followRepository.findActiveFollowsByFollowingId(actorUserId).stream()
                    .map(follow -> follow.getFollowerId())
                    .filter(Objects::nonNull)
                    .forEach(socialGraphUserIds::add);
            requestedRecipients.retainAll(socialGraphUserIds);
            if (requestedRecipients.isEmpty()) {
                logger.info("Skipped challenge invite notifications outside actor userId={} social graph", actorUserId);
                return;
            }

            Map<Long, UserPreferences> prefsByUserId = preferencesRepository.findByUserIdIn(requestedRecipients).stream()
                    .collect(Collectors.toMap(UserPreferences::getUserId, prefs -> prefs));
            List<User> recipients = userRepository.findAllById(requestedRecipients);
            List<Notification> toSave = new ArrayList<>();
            List<PushDispatch> pushes = new ArrayList<>();

            String challengeTitle = safe(challenge.getTitle());
            String message = displayName(actor) + " invited you to join"
                    + (challengeTitle.isBlank() ? " a challenge" : " " + challengeTitle);

            for (User recipient : recipients) {
                if (!Boolean.TRUE.equals(recipient.getActive())
                        || userBlockRepository.existsActiveBlockBetween(recipient.getId(), actorUserId)) {
                    continue;
                }
                UserPreferences prefs = prefsByUserId.get(recipient.getId());
                ChallengeInviteReservation reservation = reserveChallengeInviteDelivery(
                        actorUserId, challenge.getId(), recipient.getId());
                if (reservation.status() == INVITE_DELIVERY_QUOTA_EXHAUSTED) {
                    logger.warn("Daily challenge invite quota exhausted for actor userId={}", actorUserId);
                    break;
                }
                if (reservation.status() != INVITE_DELIVERY_RESERVED)
                    continue;
                deliveryReservations.add(reservation);

                String actionObject = toJson(baseChallengeInvitePayload(actor, recipient, challenge));
                if (allowsInbox(recipient)) {
                    toSave.add(buildNotification(recipient.getId(), TYPE_CHALLENGE_INVITE, message, actionObject));
                    if (allowsPush(recipient, prefs, TYPE_CHALLENGE_INVITE)) {
                        pushes.add(new PushDispatch(recipient.getId(), TYPE_CHALLENGE_INVITE, message, actionObject));
                    }
                }
            }

            if (!toSave.isEmpty()) {
                releaseChallengeInviteReservationsAfterRollback(deliveryReservations);
                notificationRepository.saveAll(toSave);
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
    @Transactional
    public int notifyChallengeJoined(Challenge challenge, Long joiningUserId, Collection<Long> recipientUserIds) {
        if (challenge == null || joiningUserId == null || recipientUserIds == null || recipientUserIds.isEmpty()) {
            return 0;
        }

        Set<Long> normalizedRecipients = recipientUserIds.stream()
                .filter(Objects::nonNull)
                .filter(userId -> userId > 0)
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

        Map<Long, UserPreferences> prefsByUserId = preferencesRepository.findByUserIdIn(normalizedRecipients).stream()
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
            Long joiningUserId,
            Collection<Long> recipientUserIds
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
    public Notification notifyGlory(Submission submission, Long actorUserId) {
        if (submission == null || Objects.equals(submission.getUserId(), actorUserId)) {
            return null;
        }
        return notifySubmissionOwner(submission, actorUserId, null, TYPE_GLORY, "gloried your submission");
    }

    @Override
    @Async("asyncExecutor")
    @Transactional
    public void notifyGloryAsync(Submission submission, Long actorUserId) {
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
    public Notification notifyComment(Submission submission, Long actorUserId, Long commentId) {
        if (submission == null || Objects.equals(submission.getUserId(), actorUserId)) {
            return null;
        }
        return notifySubmissionOwner(submission, actorUserId, commentId, TYPE_COMMENT, "commented on your submission");
    }

    @Override
    @Async("asyncExecutor")
    @Transactional
    public void notifyCommentAsync(Submission submission, Long actorUserId, Long commentId) {
        try {
            notifyComment(submission, actorUserId, commentId);
        } catch (Exception e) {
            logger.warn("Failed to create comment notification for commentId={}: {}", commentId, e.getClass().getSimpleName());
        }
    }

    private Notification notifySubmissionOwner(Submission submission,
                                               Long actorUserId,
                                               Long commentId,
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

    private void invalidateUnreadCount(Long userId) {
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

    private void dispatchPushAfterCommit(Long userId, String type, String message, String actionObject) {
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

    private Notification buildNotification(Long userId, String type, String message, String actionObject) {
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
        if (!IMMEDIATE_PUSH_TYPES.contains(normalizedType)) {
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
            Long actorUserId,
            Long challengeId,
            Long recipientUserId
    ) {
        if (actorUserId == null || challengeId == null || recipientUserId == null) {
            return new ChallengeInviteReservation(
                    INVITE_DELIVERY_QUOTA_EXHAUSTED, "", "");
        }

        Instant now = Instant.now();
        LocalDate utcDate = now.atZone(ZoneOffset.UTC).toLocalDate();
        Instant nextUtcDay = utcDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        long quotaTtlMillis = Math.max(1_000L, Duration.between(now, nextUtcDay).toMillis());
        String quotaKey = "notification-quota:challenge-invite:{" + actorUserId + "}:" + utcDate;
        String dedupeKey = "notification-dedupe:challenge-invite:{" + actorUserId + "}:"
                + challengeId + ":" + recipientUserId;
        Long result = redisTemplate.execute(
                RESERVE_CHALLENGE_INVITE_SCRIPT,
                List.of(quotaKey, dedupeKey),
                Integer.toString(MAX_CHALLENGE_INVITES_PER_UTC_DAY),
                Long.toString(quotaTtlMillis),
                Long.toString(Duration.ofHours(24).toMillis())
        );
        return new ChallengeInviteReservation(
                result == null ? INVITE_DELIVERY_QUOTA_EXHAUSTED : result,
                quotaKey,
                dedupeKey
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
                redisTemplate.execute(
                        RELEASE_CHALLENGE_INVITE_SCRIPT,
                        List.of(reservation.quotaKey(), reservation.dedupeKey())
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

    private String firstMediaUrl(String mediaUrls) {
        if (mediaUrls == null || mediaUrls.isBlank()) {
            return null;
        }
        String[] parts = mediaUrls.split(",");
        return parts.length == 0 ? null : parts[0].trim();
    }

    private String readableProfilePic(Long userId, String value) {
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
