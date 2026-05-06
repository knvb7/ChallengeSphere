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
import com.ironside.challengesphere.service.EmailService;
import com.ironside.challengesphere.service.NotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private static final int EMAIL_NOTIFICATIONS_BIT = 2;

    private static final String TYPE_FOLLOW = "FOLLOW";
    private static final String TYPE_SUBMISSION = "SUBMISSION";
    private static final String TYPE_GLORY = "GLORY";
    private static final String TYPE_COMMENT = "COMMENT";
    private static final String TYPE_CHALLENGE_INVITE = "CHALLENGE_INVITE";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ChallengeRepository challengeRepository;
    private final FollowRepository followRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    public NotificationServiceImpl(NotificationRepository notificationRepository,
                                   UserRepository userRepository,
                                   ChallengeRepository challengeRepository,
                                   FollowRepository followRepository,
                                   UserBlockRepository userBlockRepository,
                                   UserPreferencesRepository preferencesRepository,
                                   EmailService emailService,
                                   ObjectMapper objectMapper) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.challengeRepository = challengeRepository;
        this.followRepository = followRepository;
        this.userBlockRepository = userBlockRepository;
        this.preferencesRepository = preferencesRepository;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Notification createNotification(Long userId, String type, String message, String actionObject) {
        Notification saved = notificationRepository.save(buildNotification(userId, type, message, actionObject));
        logger.info("Created notification id={} for userId={} type={}", saved.getId(), userId, type);
        return saved;
    }

    @Override
    @Transactional
    public Notification createAndEmail(Long userId, String type, String message, String actionObject) {
        Notification saved = createNotification(userId, type, message, actionObject);
        userRepository.findById(userId).ifPresent(user -> {
            UserPreferences prefs = preferencesRepository.findByUserId(userId).orElse(null);
            if (allowsEmail(user, prefs, type)) {
                emailService.sendPlainEmail(user.getEmailId(), subjectFor(type), message);
            }
        });
        return saved;
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
    @Transactional
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

        String challengeTitle = safe(challenge.getTitle());
        String message = displayName(author) + " posted Day " + submission.getDayNumber()
                + (challengeTitle.isBlank() ? "" : " in " + challengeTitle);
        String actionObject = toJson(baseSubmissionPayload(author, submission, challenge));

        for (User recipient : recipients) {
            if (userBlockRepository.existsActiveBlockBetween(recipient.getId(), authorUserId)) {
                continue;
            }
            UserPreferences prefs = prefsByUserId.get(recipient.getId());
            if (allowsInApp(recipient, prefs, TYPE_SUBMISSION)) {
                toSave.add(buildNotification(recipient.getId(), TYPE_SUBMISSION, message, actionObject));
            }
            sendEmailIfAllowed(recipient, prefs, TYPE_SUBMISSION, message);
        }

        if (!toSave.isEmpty()) {
            notificationRepository.saveAll(toSave);
        }
        logger.info("Created {} submission notification(s) for submissionId={}", toSave.size(), submission.getId());
        return toSave.size();
    }

    @Override
    @Async("cacheExecutor")
    @Transactional
    public void notifyChallengeInvitesAsync(Challenge challenge, Long actorUserId, Collection<Long> requestedRecipientUserIds) {
        try {
            if (challenge == null || actorUserId == null || requestedRecipientUserIds == null || requestedRecipientUserIds.isEmpty()) {
                return;
            }

            User actor = userRepository.findById(actorUserId).orElse(null);
            if (actor == null) {
                logger.warn("Skipping challenge invite notifications; actor userId={} not found", actorUserId);
                return;
            }

            Set<Long> requestedRecipients = requestedRecipientUserIds.stream()
                    .filter(Objects::nonNull)
                    .filter(userId -> userId > 0)
                    .filter(userId -> !Objects.equals(userId, actorUserId))
                    .collect(Collectors.toSet());
            if (requestedRecipients.isEmpty()) {
                return;
            }

            Set<Long> inviteableUserIds = new HashSet<>();
            followRepository.findActiveFollowsByFollowerId(actorUserId).forEach(follow -> inviteableUserIds.add(follow.getFollowingId()));
            followRepository.findActiveFollowsByFollowingId(actorUserId).forEach(follow -> inviteableUserIds.add(follow.getFollowerId()));
            requestedRecipients.retainAll(inviteableUserIds);
            if (requestedRecipients.isEmpty()) {
                logger.info("No selected invite recipients are followers/following for challengeId={}", challenge.getId());
                return;
            }

            Map<Long, UserPreferences> prefsByUserId = preferencesRepository.findByUserIdIn(requestedRecipients).stream()
                    .collect(Collectors.toMap(UserPreferences::getUserId, prefs -> prefs));
            List<User> recipients = userRepository.findAllById(requestedRecipients);
            List<Notification> toSave = new ArrayList<>();

            String challengeTitle = safe(challenge.getTitle());
            String message = displayName(actor) + " invited you to join"
                    + (challengeTitle.isBlank() ? " a challenge" : " " + challengeTitle);

            for (User recipient : recipients) {
                if (userBlockRepository.existsActiveBlockBetween(recipient.getId(), actorUserId)) {
                    continue;
                }
                UserPreferences prefs = prefsByUserId.get(recipient.getId());
                if (!allowsChallengeInvites(prefs)) {
                    continue;
                }

                String actionObject = toJson(baseChallengeInvitePayload(actor, recipient, challenge));
                if (allowsInApp(recipient, prefs, TYPE_CHALLENGE_INVITE)) {
                    toSave.add(buildNotification(recipient.getId(), TYPE_CHALLENGE_INVITE, message, actionObject));
                }
                sendEmailIfAllowed(recipient, prefs, TYPE_CHALLENGE_INVITE, message);
            }

            if (!toSave.isEmpty()) {
                notificationRepository.saveAll(toSave);
            }
            logger.info("Created {} challenge invite notification(s) for challengeId={}", toSave.size(), challenge.getId());
        } catch (Exception e) {
            logger.warn("Failed to create challenge invite notifications for challengeId={}: {}",
                    challenge == null ? null : challenge.getId(), e.getMessage());
        }
    }

    @Override
    @Transactional
    public Notification notifyGlory(Submission submission, Long actorUserId) {
        logger.debug("Glory notification suppressed for submissionId={} actorUserId={}",
                submission == null ? null : submission.getId(), actorUserId);
        return null;
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
    @Async("cacheExecutor")
    @Transactional
    public void notifyCommentAsync(Submission submission, Long actorUserId, Long commentId) {
        try {
            notifyComment(submission, actorUserId, commentId);
        } catch (Exception e) {
            logger.warn("Failed to create comment notification for commentId={}: {}", commentId, e.getMessage());
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
        data.put("targetProfilePicUrl", recipient.get().getProfilePicUrl());
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
        if (allowsInApp(recipient, prefs, type)) {
            saved = notificationRepository.save(buildNotification(recipient.getId(), type, message, actionObject));
            logger.info("Created notification id={} for userId={} type={}", saved.getId(), recipient.getId(), type);
        } else {
            logger.debug("In-app notification suppressed by preferences for userId={} type={}", recipient.getId(), type);
        }
        sendEmailIfAllowed(recipient, prefs, type, message);
        return saved;
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

    private void sendEmailIfAllowed(User user, UserPreferences prefs, String type, String message) {
        if (allowsEmail(user, prefs, type)) {
            emailService.sendPlainEmail(user.getEmailId(), subjectFor(type), message);
        }
    }

    private boolean allowsInApp(User user, UserPreferences prefs, String type) {
        if (user == null || user.getId() == null) {
            return false;
        }
        Integer mask = user.getNotifications();
        boolean legacyPushEnabled = mask == null || (mask & PUSH_NOTIFICATIONS_BIT) == PUSH_NOTIFICATIONS_BIT;
        return legacyPushEnabled
                && (prefs == null || Boolean.TRUE.equals(prefs.getPushNotificationsEnabled()))
                && allowsType(prefs, type);
    }

    private boolean allowsEmail(User user, UserPreferences prefs, String type) {
        if (user == null || user.getEmailId() == null || user.getEmailId().isBlank()) {
            return false;
        }
        Integer mask = user.getNotifications();
        boolean legacyEmailEnabled = mask != null && (mask & EMAIL_NOTIFICATIONS_BIT) == EMAIL_NOTIFICATIONS_BIT;
        return legacyEmailEnabled
                && (prefs == null || Boolean.TRUE.equals(prefs.getEmailNotificationsEnabled()))
                && allowsType(prefs, type);
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
            default -> true;
        };
    }

    private Map<String, Object> baseActorPayload(User actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actorUserId", actor.getId());
        data.put("actorUsername", actor.getUsername());
        data.put("actorProfilePicUrl", actor.getProfilePicUrl());
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
        data.put("challengeId", challenge.getId());
        data.put("challengeTitle", challenge.getTitle());
        data.put("creatorUserId", challenge.getCreatorId());
        data.put("targetUserId", recipient.getId());
        data.put("targetUsername", recipient.getUsername());
        data.put("targetProfilePicUrl", recipient.getProfilePicUrl());
        data.put("isPrivate", challenge.getIsPrivate());
        return data;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize notification action object: {}", e.getMessage());
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

    private String displayName(User user) {
        if (user == null) {
            return "Someone";
        }
        return user.getUsername() == null || user.getUsername().isBlank()
                ? "Someone"
                : "@" + user.getUsername();
    }

    private String subjectFor(String type) {
        String normalized = safe(type).replace('_', ' ').toLowerCase();
        if (normalized.isBlank()) {
            return "[ChallengeSphere] New notification";
        }
        return "[ChallengeSphere] " + Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
