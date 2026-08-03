package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.request.RegisterPushTokenRequest;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.repository.PushDeviceTokenRepository;
import com.ironside.challengesphere.repository.MotivationReminderRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.service.PushNotificationSender;
import com.ironside.challengesphere.service.PushNotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PushNotificationServiceImpl implements PushNotificationService {

    private static final Logger logger = LogManager.getLogger(PushNotificationServiceImpl.class);
    private static final Set<String> PUSH_TYPES = Set.of(
            "FOLLOW", "GLORY", "COMMENT", "CHALLENGE_INVITE");
    private static final LocalTime SCHEDULED_CUTOFF = LocalTime.of(21, 0);

    private enum ScheduledReminder {
        PENDING_UPLOAD(LocalTime.of(9, 0), "PENDING_UPLOAD_REMINDER"),
        LEADERBOARD(LocalTime.of(17, 30), "LEADERBOARD_MOTIVATION"),
        FINISH_STRONG(LocalTime.of(20, 15), "FINISH_STRONG_REMINDER");

        private final LocalTime time;
        private final String type;

        ScheduledReminder(LocalTime time, String type) {
            this.time = time;
            this.type = type;
        }
    }

    private final PushDeviceTokenRepository tokenRepository;
    private final SubmissionRepository submissionRepository;
    private final PushNotificationSender sender;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private NotificationQuotaService notificationQuotaService;

    @Autowired
    private MotivationReminderRepository motivationReminderRepository;

    @Autowired
    private ChallengeMomentumPolicy momentumPolicy;

    private record ReminderDispatch(
            Long userId,
            LocalDate localDate,
            List<PushDeviceToken> tokens,
            NotificationQuotaService.Reservation reservation
    ) {
    }

    private record ReminderCandidate(
            Long challengeId,
            String challengeTitle,
            int dayNumber,
            int totalDays,
            int completedDays,
            boolean privateChallenge,
            Long rank,
            Long totalParticipants,
            ChallengeMomentumPolicy.Result momentum
    ) {
        boolean finalDay() {
            return dayNumber >= totalDays;
        }

        int remainingAvailable() {
            return momentum == null
                    ? Math.max(1, totalDays - dayNumber + 1)
                    : momentum.remainingAvailableCheckIns();
        }

        boolean hasComparativeRank() {
            return privateChallenge
                    && completedDays > 0
                    && rank != null
                    && rank > 0
                    && totalParticipants != null
                    && totalParticipants >= 2;
        }
    }

    @Autowired
    public PushNotificationServiceImpl(PushDeviceTokenRepository tokenRepository,
                                       SubmissionRepository submissionRepository,
                                       PushNotificationSender sender) {
        this(tokenRepository, submissionRepository, sender, Clock.systemUTC());
    }

    PushNotificationServiceImpl(PushDeviceTokenRepository tokenRepository,
                                SubmissionRepository submissionRepository,
                                PushNotificationSender sender,
                                Clock clock) {
        this.tokenRepository = tokenRepository;
        this.submissionRepository = submissionRepository;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PushDeviceToken registerToken(Long userId, RegisterPushTokenRequest request) throws ChallengeSphereException {
        if (userId == null) {
            throw new ChallengeSphereException("Authenticated user required", HttpStatus.UNAUTHORIZED, logger);
        }
        String expoPushToken = safe(request == null ? null : request.getExpoPushToken());
        if (!expoPushToken.startsWith("ExponentPushToken[") && !expoPushToken.startsWith("ExpoPushToken[")) {
            throw new ChallengeSphereException("Invalid Expo push token", HttpStatus.BAD_REQUEST, logger);
        }

        String timezone = normalizeTimezone(request.getTimezone());
        String platform = safe(request.getPlatform()).isBlank() ? "unknown" : safe(request.getPlatform()).toLowerCase();
        Timestamp now = Timestamp.from(clock.instant());

        PushDeviceToken token = tokenRepository.findByExpoPushToken(expoPushToken).orElseGet(PushDeviceToken::new);
        if (token.getUserId() != null && !token.getUserId().equals(userId)) {
            token.setLastDailyReminderSentDate(null);
        }
        token.setUserId(userId);
        token.setExpoPushToken(expoPushToken);
        token.setPlatform(platform);
        token.setTimezone(timezone);
        token.setEnabled(true);
        token.setLastSeenAt(now);
        return tokenRepository.save(token);
    }

    @Override
    @Transactional
    public void disableToken(Long userId, String expoPushToken) {
        if (userId == null || safe(expoPushToken).isBlank()) {
            return;
        }
        tokenRepository.disableToken(userId, expoPushToken.trim());
    }

    @Override
    @Async("asyncExecutor")
    public void sendInteractionPush(Long userId, String type, String message, String actionObject) {
        String normalizedType = safe(type).toUpperCase();
        if (userId == null || !PUSH_TYPES.contains(normalizedType)) {
            return;
        }

        // Re-check account state and notification preferences at the final dispatch
        // boundary so an opt-out racing an async job cannot still receive a push.
        List<PushDeviceToken> tokens = tokenRepository.findEligibleByUserIdAndType(userId, normalizedType);
        if (tokens.isEmpty()) {
            return;
        }
        ZoneId primaryZone = toZoneId(tokens.get(0).getTimezone());
        LocalDate localDate = ZonedDateTime.now(clock).withZoneSameInstant(primaryZone).toLocalDate();
        Optional<NotificationQuotaService.Reservation> reservation = reserveNotification(userId, localDate);
        if (reservation.isEmpty()) {
            logger.info("Daily push quota exhausted for userId={} type={}", userId, normalizedType);
            return;
        }

        Map<String, Object> data = parseActionObject(actionObject);
        data.put("type", normalizedType);

        String title = interactionTitle(normalizedType);
        String body = interactionBody(normalizedType, message);

        List<PushNotificationSender.PushMessage> messages = tokens.stream()
                .map(token -> new PushNotificationSender.PushMessage(token.getExpoPushToken(), title, body, data))
                .toList();
        PushNotificationSender.SendResult result;
        try {
            result = sender.send(messages);
        } catch (RuntimeException exception) {
            releaseNotification(reservation.get());
            logger.error("Push dispatch failed for userId={} type={}: {}",
                    userId, normalizedType, exception.getClass().getSimpleName());
            return;
        }
        if (result == null) {
            releaseNotification(reservation.get());
            logger.error("Push sender returned no result for userId={} type={}", userId, normalizedType);
            return;
        }
        disablePermanentlyRejectedTokens(result);
        if (result.acceptedTokens().isEmpty()) {
            releaseNotification(reservation.get());
        }
    }

    @Override
    public int sendDailyUploadReminders() {
        return sendDailyUploadReminders(Set.of());
    }

    @Override
    public int sendDailyUploadReminders(Set<Long> excludedUserIds) {
        Set<Long> normalizedExcludedUserIds = excludedUserIds == null ? Set.of() : excludedUserIds;
        List<PushDeviceToken> tokens = tokenRepository.findEnabledReminderTokens();
        List<PushNotificationSender.PushMessage> messages = new ArrayList<>();
        List<ReminderDispatch> dispatches = new ArrayList<>();
        List<PushDeviceToken> updatedTokens = new ArrayList<>();

        Map<Long, List<PushDeviceToken>> tokensByUser = tokens.stream()
                .collect(Collectors.groupingBy(
                        PushDeviceToken::getUserId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<Long, List<MotivationReminderRepository.ScheduledMotivationProjection>> pendingByUser =
                motivationReminderRepository == null || momentumPolicy == null
                        ? Map.of()
                        : motivationReminderRepository.findAllPending(Timestamp.from(clock.instant())).stream()
                                .collect(Collectors.groupingBy(
                                        MotivationReminderRepository.ScheduledMotivationProjection::getUserId,
                                        LinkedHashMap::new,
                                        Collectors.toList()
                                ));

        try {
            for (Map.Entry<Long, List<PushDeviceToken>> entry : tokensByUser.entrySet()) {
                Long userId = entry.getKey();
                if (normalizedExcludedUserIds.contains(userId)) {
                    continue;
                }
                List<PushDeviceToken> userTokens = entry.getValue();
                PushDeviceToken primaryToken = userTokens.get(0);
                ZoneId zoneId = toZoneId(primaryToken.getTimezone());
                ZonedDateTime localNow = ZonedDateTime.now(clock).withZoneSameInstant(zoneId);
                LocalDate localDate = localNow.toLocalDate();
                LocalTime localTime = localNow.toLocalTime();
                ScheduledReminder reminder = scheduledReminderAt(localTime);
                if (reminder == null) {
                    continue;
                }
                List<ReminderCandidate> pendingChallenges = motivationReminderRepository == null || momentumPolicy == null
                        ? findReminderCandidates(userId, localDate)
                        : findReminderCandidates(pendingByUser.getOrDefault(userId, List.of()), localDate);
                long pendingChallengeCount = pendingChallenges.size();
                if (pendingChallengeCount <= 0) {
                    continue;
                }
                Optional<NotificationQuotaService.Reservation> reservation = reserveNotification(
                        userId, localDate, reminder.type + ":" + localDate);
                if (reservation.isEmpty()) {
                    continue;
                }
                dispatches.add(new ReminderDispatch(userId, localDate, userTokens, reservation.get()));

                ReminderCandidate selected = pendingChallenges.get(0);
                Map<String, Object> data = reminderPayload(selected);
                data.put("type", reminder.type);
                data.put("milestoneKind", reminder.type);
                String body = scheduledBody(reminder, selected, pendingChallengeCount);
                String title = scheduledTitle(reminder, selected);

                for (PushDeviceToken token : userTokens) {
                    messages.add(new PushNotificationSender.PushMessage(
                            token.getExpoPushToken(),
                            title,
                            body,
                            data
                    ));
                }
            }
        } catch (RuntimeException exception) {
            dispatches.forEach(dispatch -> releaseNotification(dispatch.reservation()));
            logger.error("Failed to assemble daily reminder pushes; released {} reservation(s): {}",
                    dispatches.size(), exception.getClass().getSimpleName());
            return 0;
        }

        if (messages.isEmpty()) {
            return 0;
        }

        PushNotificationSender.SendResult result;
        try {
            result = sender.send(messages);
        } catch (RuntimeException exception) {
            dispatches.forEach(dispatch -> releaseNotification(dispatch.reservation()));
            logger.error("Daily reminder push dispatch failed for {} user(s): {}",
                    dispatches.size(), exception.getClass().getSimpleName());
            return 0;
        }
        if (result == null) {
            dispatches.forEach(dispatch -> releaseNotification(dispatch.reservation()));
            logger.error("Push sender returned no result for {} daily reminder user(s)", dispatches.size());
            return 0;
        }
        disablePermanentlyRejectedTokens(result);
        for (ReminderDispatch dispatch : dispatches) {
            boolean acceptedOnAnyDevice = dispatch.tokens().stream()
                    .map(PushDeviceToken::getExpoPushToken)
                    .anyMatch(result::accepted);
            if (!acceptedOnAnyDevice) {
                releaseNotification(dispatch.reservation());
                continue;
            }
            for (PushDeviceToken token : dispatch.tokens()) {
                if (!result.accepted(token.getExpoPushToken())) {
                    continue;
                }
                token.setLastDailyReminderSentDate(dispatch.localDate());
                updatedTokens.add(token);
            }
        }
        if (!updatedTokens.isEmpty()) {
            tokenRepository.saveAll(updatedTokens);
        }
        return result.acceptedTokens().size();
    }

    private List<ReminderCandidate> findReminderCandidates(Long userId, LocalDate localDate) {
        return submissionRepository.findIncompleteDailyChallengesForUser(userId, localDate).stream()
                .map(pending -> new ReminderCandidate(
                        pending.getChallengeId(),
                        pending.getChallengeTitle(),
                        pending.getDayNumber(),
                        pending.getDayNumber(),
                        0,
                        false,
                        null,
                        null,
                        null
                ))
                .sorted(reminderPriority())
                .toList();
    }

    private List<ReminderCandidate> findReminderCandidates(
            List<MotivationReminderRepository.ScheduledMotivationProjection> pendingRows,
            LocalDate localDate
    ) {
        return pendingRows.stream()
                .filter(pending -> localDate.equals(pending.getLocalDate()))
                .map(pending -> {
                    Optional<ChallengeMomentumPolicy.Result> momentum = momentumPolicy.evaluate(
                            new ChallengeMomentumPolicy.Input(
                                    pending.getChallengeMode(),
                                    Boolean.TRUE.equals(pending.getChallengeActive()),
                                    Boolean.TRUE.equals(pending.getParticipantCompleted()),
                                    Boolean.TRUE.equals(pending.getPrivateChallenge()),
                                    Boolean.TRUE.equals(pending.getPrivateLobby()),
                                    pending.getStartDate(),
                                    pending.getTotalDays(),
                                    pending.getCompletedDays(),
                                    false,
                                    localDate
                            )
                    );
                    return momentum.map(result -> new ReminderCandidate(
                            pending.getChallengeId(),
                            pending.getChallengeTitle(),
                            pending.getDayNumber(),
                            pending.getTotalDays(),
                            pending.getCompletedDays(),
                            Boolean.TRUE.equals(pending.getPrivateChallenge()),
                            pending.getRank(),
                            pending.getTotalParticipants(),
                            result
                    )).orElse(null);
                })
                .filter(java.util.Objects::nonNull)
                .sorted(reminderPriority())
                .toList();
    }

    private Comparator<ReminderCandidate> reminderPriority() {
        return Comparator
                .comparing(ReminderCandidate::finalDay).reversed()
                .thenComparingInt(ReminderCandidate::remainingAvailable)
                .thenComparing(ReminderCandidate::hasComparativeRank, Comparator.reverseOrder())
                .thenComparing(ReminderCandidate::challengeId);
    }

    private ScheduledReminder scheduledReminderAt(LocalTime localTime) {
        if (localTime == null || !localTime.isBefore(SCHEDULED_CUTOFF)) {
            return null;
        }
        for (ScheduledReminder reminder : ScheduledReminder.values()) {
            if (!localTime.isBefore(reminder.time)
                    && localTime.isBefore(reminder.time.plusMinutes(15))) {
                return reminder;
            }
        }
        return null;
    }

    private String scheduledTitle(ScheduledReminder reminder, ReminderCandidate selected) {
        return switch (reminder) {
            case PENDING_UPLOAD -> "Upload reminder 📸";
            case LEADERBOARD -> "Keep climbing 🏆";
            case FINISH_STRONG -> reminderTitle(selected);
        };
    }

    private String scheduledBody(
            ScheduledReminder reminder,
            ReminderCandidate selected,
            long pendingChallengeCount
    ) {
        return switch (reminder) {
            case PENDING_UPLOAD -> pendingChallengeCount == 1
                    ? selected.challengeTitle() + " is waiting for today's upload. Let's go! ⚡"
                    : pendingChallengeCount + " challenges are waiting for today's uploads. Let's go! ⚡";
            case LEADERBOARD -> selected.hasComparativeRank()
                    ? "You're #" + selected.rank() + " in " + selected.challengeTitle()
                            + "—upload today and keep climbing. 🚀"
                    : "Upload today to move up the leaderboard in " + selected.challengeTitle() + ". 🚀";
            case FINISH_STRONG -> reminderBody(selected, pendingChallengeCount);
        };
    }

    private Map<String, Object> reminderPayload(ReminderCandidate selected) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("challengeId", selected.challengeId());
        data.put("challengeTitle", selected.challengeTitle());
        data.put("milestoneKind", "DAILY_REMINDER");
        if (selected.momentum() != null) {
            data.put("totalDays", selected.momentum().totalDays());
            data.put("completedDays", selected.momentum().completedDays());
            data.put("remainingRequiredCheckIns", selected.momentum().remainingRequiredCheckIns());
            data.put("remainingAvailableCheckIns", selected.momentum().remainingAvailableCheckIns());
            data.put("submittedToday", selected.momentum().submittedToday());
        }
        if (selected.hasComparativeRank()) {
            data.put("rank", selected.rank());
            data.put("totalParticipants", selected.totalParticipants());
        }
        return data;
    }

    private String reminderTitle(ReminderCandidate selected) {
        if (selected.momentum() == null) {
            return "Your streak is waiting 🔥";
        }
        return switch (selected.momentum().state()) {
            case FINAL_STRETCH -> "Final stretch 🚀";
            case FINISH_STRONG -> "Finish strong 💪";
            case NONE -> "Your check-in is waiting ⏰";
        };
    }

    private String reminderBody(ReminderCandidate selected, long pendingChallengeCount) {
        if (selected.momentum() == null) {
            String challengeLabel = pendingChallengeCount == 1 ? "challenge needs" : "challenges need";
            String streakLabel = pendingChallengeCount == 1 ? "streak" : "streaks";
            return pendingChallengeCount + " " + challengeLabel
                    + " your check-in today. Keep the momentum going and protect your "
                    + streakLabel + " — you've got this!";
        }

        if (selected.privateChallenge() && selected.completedDays() == 0) {
            return "Upload your first check-in in " + selected.challengeTitle()
                    + " to join the leaderboard." + otherChallengesSuffix(pendingChallengeCount);
        }

        String progress = switch (selected.momentum().state()) {
            case FINAL_STRETCH -> "Final stretch — " + selected.momentum().remainingRequiredCheckIns()
                    + (selected.momentum().remainingRequiredCheckIns() == 1 ? " check-in" : " check-ins")
                    + " to go in " + selected.challengeTitle() + ".";
            case FINISH_STRONG -> "Finish strong — " + selected.momentum().remainingAvailableCheckIns()
                    + (selected.momentum().remainingAvailableCheckIns() == 1
                    ? " check-in opportunity remains in "
                    : " check-in opportunities remain in ")
                    + selected.challengeTitle() + ".";
            case NONE -> selected.challengeTitle() + " needs your check-in today.";
        };

        if (selected.hasComparativeRank()) {
            String rankCopy = selected.rank() == 1
                    ? " You're #1 in your crew—upload today to hold the lead."
                    : " You're #" + selected.rank() + " in your crew—upload today and keep climbing.";
            return progress + rankCopy + otherChallengesSuffix(pendingChallengeCount);
        }
        return progress + " Upload today and keep moving." + otherChallengesSuffix(pendingChallengeCount);
    }

    private String otherChallengesSuffix(long pendingChallengeCount) {
        long otherCount = pendingChallengeCount - 1;
        if (otherCount <= 0) {
            return "";
        }
        return " " + otherCount + (otherCount == 1 ? " other challenge is" : " other challenges are")
                + " waiting today.";
    }

    private String interactionTitle(String type) {
        return switch (type) {
            case "FOLLOW" -> "New follower 👋";
            case "GLORY" -> "New glory ✨";
            case "COMMENT" -> "New comment 💬";
            case "CHALLENGE_INVITE" -> "Challenge invite 🎯";
            default -> "ChallengeSphere 🌟";
        };
    }

    private String interactionBody(String type, String message) {
        String cleanMessage = safe(message);
        String emoji = switch (type) {
            case "FOLLOW" -> "👋";
            case "GLORY" -> "🙌";
            case "COMMENT" -> "💬";
            case "CHALLENGE_INVITE" -> "🔥";
            default -> "✨";
        };
        return cleanMessage.isBlank() ? emoji : cleanMessage + " " + emoji;
    }

    private Optional<NotificationQuotaService.Reservation> reserveNotification(
            Long recipientUserId,
            LocalDate localDate
    ) {
        if (notificationQuotaService == null) {
            return Optional.of(testReservation(recipientUserId));
        }
        return notificationQuotaService.reserve(recipientUserId, localDate);
    }

    private Optional<NotificationQuotaService.Reservation> reserveNotification(
            Long recipientUserId,
            LocalDate localDate,
            String dedupeKey
    ) {
        if (notificationQuotaService == null) {
            return Optional.of(testReservation(recipientUserId));
        }
        return notificationQuotaService.reserve(recipientUserId, localDate, dedupeKey);
    }

    private NotificationQuotaService.Reservation testReservation(Long recipientUserId) {
        return new NotificationQuotaService.Reservation(
                recipientUserId,
                "test-quota",
                "test-dedupe",
                "test-member",
                false
        );
    }

    private void releaseNotification(NotificationQuotaService.Reservation reservation) {
        if (notificationQuotaService != null) {
            notificationQuotaService.release(reservation);
        }
    }

    private void disablePermanentlyRejectedTokens(PushNotificationSender.SendResult result) {
        result.rejectedTokens().forEach((token, reason) -> {
            if ("DeviceNotRegistered".equalsIgnoreCase(reason)) {
                try {
                    tokenRepository.disableTokenByExpoPushToken(token);
                } catch (RuntimeException exception) {
                    logger.warn("Failed to disable invalid Expo token: {}",
                            exception.getClass().getSimpleName());
                }
            }
        });
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
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String normalizeTimezone(String timezone) throws ChallengeSphereException {
        String value = safe(timezone).isBlank() ? "UTC" : timezone.trim();
        try {
            return ZoneId.of(value).getId();
        } catch (DateTimeException e) {
            throw new ChallengeSphereException("Invalid timezone", HttpStatus.BAD_REQUEST, logger);
        }
    }

    private ZoneId toZoneId(String timezone) {
        try {
            return ZoneId.of(safe(timezone).isBlank() ? "UTC" : timezone);
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
