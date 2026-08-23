package com.ironside.challengesphere.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ironside.challengesphere.api.response.LeaderboardResponse;
import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.Notification;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.repository.ChallengeCrewLifecycleRepository;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.NotificationRepository;
import com.ironside.challengesphere.repository.MotivationReminderRepository;
import com.ironside.challengesphere.repository.SubmissionRepository;
import com.ironside.challengesphere.repository.UserChallengeMilestoneRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserPreferencesRepository;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.service.LeaderboardService;
import com.ironside.challengesphere.service.PushNotificationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Persists one-time goal and private-rank milestone notifications. */
@Service
public class MotivationMilestoneService {

    static final String TYPE_MOMENTUM_MILESTONE = "MOMENTUM_MILESTONE";
    static final String FINAL_STRETCH = "FINAL_STRETCH";
    static final String FINISH_STRONG = "FINISH_STRONG";
    static final String TOP_THREE = "TOP_THREE";
    static final String FIRST_PLACE = "FIRST_PLACE";

    private static final Logger logger = LogManager.getLogger(MotivationMilestoneService.class);
    private static final LocalTime EVENING_START = LocalTime.of(19, 0);
    private static final LocalTime EVENING_END = LocalTime.of(20, 0);

    private final ChallengeRepository challengeRepository;
    private final UserRepository userRepository;
    private final UserChallengeRepository userChallengeRepository;
    private final ChallengeCrewLifecycleRepository lifecycleRepository;
    private final SubmissionRepository submissionRepository;
    private final MotivationReminderRepository reminderRepository;
    private final UserPreferencesRepository preferencesRepository;
    private final UserChallengeMilestoneRepository milestoneRepository;
    private final NotificationRepository notificationRepository;
    private final LeaderboardService leaderboardService;
    private final PushNotificationService pushNotificationService;
    private final ChallengeMomentumPolicy momentumPolicy;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public MotivationMilestoneService(
            ChallengeRepository challengeRepository,
            UserRepository userRepository,
            UserChallengeRepository userChallengeRepository,
            ChallengeCrewLifecycleRepository lifecycleRepository,
            SubmissionRepository submissionRepository,
            MotivationReminderRepository reminderRepository,
            UserPreferencesRepository preferencesRepository,
            UserChallengeMilestoneRepository milestoneRepository,
            NotificationRepository notificationRepository,
            LeaderboardService leaderboardService,
            PushNotificationService pushNotificationService,
            ChallengeMomentumPolicy momentumPolicy
    ) {
        this(
                challengeRepository,
                userRepository,
                userChallengeRepository,
                lifecycleRepository,
                submissionRepository,
                reminderRepository,
                preferencesRepository,
                milestoneRepository,
                notificationRepository,
                leaderboardService,
                pushNotificationService,
                momentumPolicy,
                Clock.systemUTC()
        );
    }

    MotivationMilestoneService(
            ChallengeRepository challengeRepository,
            UserRepository userRepository,
            UserChallengeRepository userChallengeRepository,
            ChallengeCrewLifecycleRepository lifecycleRepository,
            SubmissionRepository submissionRepository,
            MotivationReminderRepository reminderRepository,
            UserPreferencesRepository preferencesRepository,
            UserChallengeMilestoneRepository milestoneRepository,
            NotificationRepository notificationRepository,
            LeaderboardService leaderboardService,
            PushNotificationService pushNotificationService,
            ChallengeMomentumPolicy momentumPolicy,
            Clock clock
    ) {
        this.challengeRepository = challengeRepository;
        this.userRepository = userRepository;
        this.userChallengeRepository = userChallengeRepository;
        this.lifecycleRepository = lifecycleRepository;
        this.submissionRepository = submissionRepository;
        this.reminderRepository = reminderRepository;
        this.preferencesRepository = preferencesRepository;
        this.milestoneRepository = milestoneRepository;
        this.notificationRepository = notificationRepository;
        this.leaderboardService = leaderboardService;
        this.pushNotificationService = pushNotificationService;
        this.momentumPolicy = momentumPolicy;
        this.objectMapper = new ObjectMapper();
        this.clock = clock;
    }

    /**
     * Existing evening scheduler pre-pass for all motivation-opted-in active participants.
     * Inbox claims do not require global push or a device token; private rank remains
     * submission-driven.
     *
     * @return users with at least one newly persisted milestone, so the ordinary reminder can be
     *         suppressed deterministically while the milestone push is dispatched after commit.
     */
    @Transactional
    public Set<String> evaluateEveningProgressMilestones() {
        Set<String> newlyClaimedUserIds = new java.util.LinkedHashSet<>();

        for (MotivationReminderRepository.EveningUserProjection candidate
                : reminderRepository.findMotivationUsers()) {
            String userId = candidate.getUserId();
            if (userId == null) {
                continue;
            }

            // A latest registered-device timezone is preferred. Users who never registered a
            // device are evaluated in UTC, keeping inbox milestones independent of push access.
            ZoneId zoneId = toZoneId(candidate.getTimezone());
            ZonedDateTime localNow = ZonedDateTime.now(clock).withZoneSameInstant(zoneId);
            if (localNow.toLocalTime().isBefore(EVENING_START)
                    || !localNow.toLocalTime().isBefore(EVENING_END)) {
                continue;
            }
            UserPreferences preferences = preferencesRepository.findByUserId(userId).orElse(null);
            if (preferences != null && !Boolean.TRUE.equals(preferences.getNotifyOnMotivation())) {
                continue;
            }

            LocalDate localDate = localNow.toLocalDate();
            for (MotivationReminderRepository.PendingMotivationProjection pending
                    : reminderRepository.findPendingForUser(userId, localDate)) {
                Optional<ChallengeMomentumPolicy.Result> result = momentumPolicy.evaluate(
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
                if (result.isEmpty() || result.get().state() == ChallengeMomentumPolicy.State.NONE) {
                    continue;
                }

                Challenge challenge = reminderChallenge(pending);
                if (createProgressMilestone(userId, challenge, result.get())) {
                    newlyClaimedUserIds.add(userId);
                }
            }
        }
        return Set.copyOf(newlyClaimedUserIds);
    }

    /**
     * Runs after the submission transaction has committed. The claim and inbox row are then
     * committed together in this transaction; phone push is dispatched only after this commit.
     */
    @Transactional
    public void evaluateAfterSubmission(
            String userId,
            String challengeId,
            LocalDate currentDate,
            boolean evaluateRank
    ) {
        if (userId == null || challengeId == null || currentDate == null) {
            return;
        }

        Challenge challenge = challengeRepository.findById(challengeId).orElse(null);
        User user = userRepository.findById(userId).orElse(null);
        UserChallenge participation = userChallengeRepository
                .findByUserIdAndChallengeId(userId, challengeId)
                .orElse(null);
        if (!isEligibleUserAndParticipation(user, participation)
                || challenge == null
                || !isEligibleChallenge(challenge, currentDate)) {
            return;
        }

        UserPreferences preferences = preferencesRepository.findByUserId(userId).orElse(null);
        if (!allowsMotivationInbox(user, preferences)) {
            return;
        }

        boolean privateChallenge = Boolean.TRUE.equals(challenge.getIsPrivate());
        boolean privateLobby = privateChallenge && lifecycleRepository.findById(challengeId)
                .map(lifecycle -> lifecycle.getStartedAt() == null)
                .orElse(true);
        if (privateLobby) {
            return;
        }

        List<Submission> submissions = submissionRepository
                .findActiveByUserIdAndChallengeId(userId, challengeId);
        Set<Integer> completedDayNumbers = submissions.stream()
                .filter(submission -> Boolean.TRUE.equals(submission.getActive()))
                .map(Submission::getDayNumber)
                .filter(day -> day != null && day >= 1 && day <= challenge.getNumberOfDays())
                .collect(Collectors.toSet());
        int currentDay = Math.toIntExact(ChronoUnit.DAYS.between(challenge.getStartDate(), currentDate)) + 1;
        boolean submittedToday = completedDayNumbers.contains(currentDay);

        Optional<ChallengeMomentumPolicy.Result> momentum = momentumPolicy.evaluate(
                new ChallengeMomentumPolicy.Input(
                        challenge.getChallengeMode(),
                        Boolean.TRUE.equals(challenge.getActive()),
                        Boolean.TRUE.equals(participation.getCompleted()),
                        privateChallenge,
                        privateLobby,
                        challenge.getStartDate(),
                        challenge.getNumberOfDays(),
                        completedDayNumbers.size(),
                        submittedToday,
                        currentDate
                )
        );

        LeaderboardResponse.UserRankDTO privateRank = evaluateRank && privateChallenge
                ? resolvePrivateRank(challengeId, userId)
                : null;

        momentum.filter(result -> result.state() != ChallengeMomentumPolicy.State.NONE)
                .ifPresent(result -> createProgressMilestone(userId, challenge, result));
        createRankMilestone(userId, challenge, privateRank, momentum.orElse(null));
    }

    private boolean isEligibleUserAndParticipation(User user, UserChallenge participation) {
        return user != null
                && user.getId() != null
                && !Boolean.FALSE.equals(user.getActive())
                && participation != null
                && Boolean.TRUE.equals(participation.getActive())
                && !Boolean.TRUE.equals(participation.getCompleted())
                && !Boolean.TRUE.equals(participation.getBanned());
    }

    private boolean allowsMotivationInbox(User user, UserPreferences preferences) {
        if (preferences != null) {
            return Boolean.TRUE.equals(preferences.getNotifyOnMotivation());
        }
        // A missing row uses the same inheritance rule as lazy preference creation. Once a
        // dedicated row exists, global phone-push state no longer controls inbox delivery.
        Integer legacyNotificationMask = user == null ? null : user.getNotifications();
        return legacyNotificationMask == null || (legacyNotificationMask & 1) == 1;
    }

    private boolean isEligibleChallenge(Challenge challenge, LocalDate currentDate) {
        if (!Boolean.TRUE.equals(challenge.getActive())
                || !Challenge.MODE_DAILY.equalsIgnoreCase(challenge.getChallengeMode())
                || challenge.getStartDate() == null
                || challenge.getNumberOfDays() == null
                || challenge.getNumberOfDays() <= 0) {
            return false;
        }
        return !currentDate.isBefore(challenge.getStartDate())
                && currentDate.isBefore(challenge.getStartDate().plusDays(challenge.getNumberOfDays()));
    }

    private LeaderboardResponse.UserRankDTO resolvePrivateRank(String challengeId, String userId) {
        try {
            LeaderboardResponse.UserRankDTO rank = leaderboardService.getUserRank(
                    challengeId, userId, userId);
            if (rank == null
                    || rank.getRank() == null
                    || rank.getTotalParticipants() == null
                    || rank.getTotalParticipants() < 2) {
                return null;
            }
            return rank;
        } catch (ChallengeSphereException | RuntimeException exception) {
            logger.warn("Could not evaluate private rank milestone for userId={} challengeId={}: {}",
                    userId, challengeId, exception.getClass().getSimpleName());
            return null;
        }
    }

    private boolean createProgressMilestone(
            String userId,
            Challenge challenge,
            ChallengeMomentumPolicy.Result momentum
    ) {
        String code = momentum.state() == ChallengeMomentumPolicy.State.FINAL_STRETCH
                ? FINAL_STRETCH
                : FINISH_STRONG;
        String message = momentum.state() == ChallengeMomentumPolicy.State.FINAL_STRETCH
                ? "Final stretch: " + momentum.remainingRequiredCheckIns()
                        + checkInLabel(momentum.remainingRequiredCheckIns()) + " to go in " + safeTitle(challenge) + "."
                : "Finish strong: " + momentum.remainingAvailableCheckIns()
                        + opportunityLabel(momentum.remainingAvailableCheckIns())
                        + " remain in " + safeTitle(challenge) + ".";

        Map<String, Object> payload = basePayload(challenge, code, momentum);
        return claimAndNotify(userId, challenge.getId(), code, message, payload);
    }

    private void createRankMilestone(
            String userId,
            Challenge challenge,
            LeaderboardResponse.UserRankDTO rank,
            ChallengeMomentumPolicy.Result momentum
    ) {
        if (rank == null || rank.getRank() < 1 || rank.getRank() > 3) {
            return;
        }

        if (rank.getRank() == 1) {
            // Direct entry at #1 consumes TOP_THREE without producing a lower-priority alert.
            milestoneRepository.claim(userId, challenge.getId(), TOP_THREE);
            Map<String, Object> payload = basePayload(challenge, FIRST_PLACE, momentum);
            payload.put("rank", rank.getRank());
            payload.put("totalParticipants", rank.getTotalParticipants());
            claimAndNotify(
                    userId,
                    challenge.getId(),
                    FIRST_PLACE,
                    "You're #1 in " + safeTitle(challenge) + ". Keep showing up to hold the lead.",
                    payload
            );
            return;
        }

        Map<String, Object> payload = basePayload(challenge, TOP_THREE, momentum);
        payload.put("rank", rank.getRank());
        payload.put("totalParticipants", rank.getTotalParticipants());
        claimAndNotify(
                userId,
                challenge.getId(),
                TOP_THREE,
                "You're #" + rank.getRank() + " of " + rank.getTotalParticipants()
                        + " in " + safeTitle(challenge) + ". Keep your momentum.",
                payload
        );
    }

    private boolean claimAndNotify(
            String userId,
            String challengeId,
            String milestoneCode,
            String message,
            Map<String, Object> payload
    ) {
        if (milestoneRepository.claim(userId, challengeId, milestoneCode) == 0) {
            return false;
        }

        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setType(TYPE_MOMENTUM_MILESTONE);
        notification.setMessage(message);
        notification.setActionObject(toJson(payload));
        notification.setSentAt(Timestamp.from(clock.instant()));
        Notification saved = notificationRepository.saveAndFlush(notification);

        payload.put("notificationId", saved.getId());
        saved.setActionObject(toJson(payload));
        notificationRepository.save(saved);
        milestoneRepository.attachNotification(userId, challengeId, milestoneCode, saved.getId());

        dispatchPushAfterCommit(userId, message, saved.getActionObject());
        return true;
    }

    private Map<String, Object> basePayload(
            Challenge challenge,
            String milestoneCode,
            ChallengeMomentumPolicy.Result momentum
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("challengeId", challenge.getId());
        payload.put("challengeTitle", challenge.getTitle());
        payload.put("milestoneKind", milestoneCode);
        if (momentum != null) {
            payload.put("totalDays", momentum.totalDays());
            payload.put("completedDays", momentum.completedDays());
            payload.put("remainingRequiredCheckIns", momentum.remainingRequiredCheckIns());
            payload.put("remainingAvailableCheckIns", momentum.remainingAvailableCheckIns());
            payload.put("submittedToday", momentum.submittedToday());
        }
        return payload;
    }

    private void dispatchPushAfterCommit(String userId, String message, String actionObject) {
        Runnable dispatch = () -> pushNotificationService.sendInteractionPush(
                userId, TYPE_MOMENTUM_MILESTONE, message, actionObject);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
            return;
        }
        dispatch.run();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize momentum notification", exception);
        }
    }

    private String safeTitle(Challenge challenge) {
        return challenge.getTitle() == null || challenge.getTitle().isBlank()
                ? "your challenge"
                : challenge.getTitle().trim();
    }

    private String checkInLabel(int count) {
        return count == 1 ? " check-in" : " check-ins";
    }

    private String opportunityLabel(int count) {
        return count == 1 ? " check-in opportunity" : " check-in opportunities";
    }

    private Challenge reminderChallenge(MotivationReminderRepository.PendingMotivationProjection pending) {
        Challenge challenge = new Challenge();
        challenge.setId(pending.getChallengeId());
        challenge.setTitle(pending.getChallengeTitle());
        challenge.setChallengeMode(pending.getChallengeMode());
        challenge.setActive(pending.getChallengeActive());
        challenge.setIsPrivate(pending.getPrivateChallenge());
        challenge.setStartDate(pending.getStartDate());
        challenge.setNumberOfDays(pending.getTotalDays());
        return challenge;
    }

    private ZoneId toZoneId(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone.trim());
        } catch (DateTimeException exception) {
            return ZoneId.of("UTC");
        }
    }
}
