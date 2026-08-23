package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
import com.ironside.challengesphere.model.LeaderboardEntry;
import com.ironside.challengesphere.model.PushDeviceToken;
import com.ironside.challengesphere.model.Submission;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.model.UserPreferences;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Read-model composer for local-evening motivation reminders. */
@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class MotivationReminderRepository {

    private static final int MAX_PARTITION_ITEMS = 100_000;
    private static final int LEGACY_PUSH_NOTIFICATION_BIT = 1;

    private final DynamoDomainStore store;

    public MotivationReminderRepository(DynamoDomainStore store) {
        this.store = store;
    }

    public List<EveningUserProjection> findMotivationUsers() {
        List<UserChallenge> memberships = reminderCandidates();
        CandidateContext context = context(memberships);
        Map<String, EveningUserProjection> users = new LinkedHashMap<>();

        for (UserChallenge membership : memberships) {
            User user = context.users().get(membership.getUserId());
            Challenge challenge = context.challenges().get(membership.getChallengeId());
            if (!eligibleMembership(membership)
                    || !activeUser(user)
                    || !eligibleChallenge(challenge, context.lifecycles())) {
                continue;
            }
            Optional<UserPreferences> preferences = preferences(membership.getUserId());
            boolean optedIn = preferences
                    .map(value -> !Boolean.FALSE.equals(value.getNotifyOnMotivation()))
                    .orElseGet(() -> legacyPushEnabled(user));
            if (!optedIn) {
                continue;
            }
            String timezone = normalizedTimezone(latestToken(membership.getUserId(), false)
                    .map(PushDeviceToken::getTimezone)
                    .filter(value -> value != null && !value.isBlank())
                    .orElse("UTC"));
            users.putIfAbsent(membership.getUserId(), new EveningUserRow(
                    membership.getUserId(), timezone
            ));
        }
        return List.copyOf(users.values());
    }

    public List<PendingMotivationProjection> findPendingForUser(
            String userId,
            LocalDate localDate
    ) {
        if (userId == null || userId.isBlank() || localDate == null) {
            return List.of();
        }
        List<UserChallenge> memberships = activeMembershipsForUser(userId);
        CandidateContext context = context(memberships);
        List<PendingMotivationProjection> rows = new ArrayList<>();
        for (UserChallenge membership : memberships) {
            Challenge challenge = context.challenges().get(membership.getChallengeId());
            pendingRow(membership, challenge, localDate, context.lifecycles())
                    .ifPresent(rows::add);
        }
        return rows.stream()
                .sorted(Comparator.comparing(PendingMotivationProjection::getStartDate)
                        .thenComparing(PendingMotivationProjection::getChallengeId))
                .toList();
    }

    public List<ScheduledMotivationProjection> findAllPending(Timestamp now) {
        if (now == null) {
            return List.of();
        }
        List<UserChallenge> memberships = reminderCandidates();
        CandidateContext context = context(memberships);
        Map<String, Optional<UserPreferences>> preferenceCache = new LinkedHashMap<>();
        Map<String, Optional<PushDeviceToken>> tokenCache = new LinkedHashMap<>();
        List<ScheduledMotivationProjection> rows = new ArrayList<>();

        for (UserChallenge membership : memberships) {
            String userId = membership.getUserId();
            User user = context.users().get(userId);
            Challenge challenge = context.challenges().get(membership.getChallengeId());
            if (!eligibleMembership(membership)
                    || !activeUser(user)
                    || !legacyPushEnabled(user)
                    || !eligibleChallenge(challenge, context.lifecycles())) {
                continue;
            }

            Optional<UserPreferences> preferences = preferenceCache.computeIfAbsent(
                    userId, this::preferences
            );
            if (preferences.map(value -> Boolean.FALSE.equals(value.getPushNotificationsEnabled())
                    || Boolean.FALSE.equals(value.getNotifyOnMotivation())).orElse(false)) {
                continue;
            }
            Optional<PushDeviceToken> token = tokenCache.computeIfAbsent(
                    userId, value -> latestToken(value, true)
            );
            if (token.isEmpty()) {
                continue;
            }
            String timezone = normalizedTimezone(token.get().getTimezone());
            LocalDate localDate = localDate(now.toInstant(), timezone);
            pendingRow(membership, challenge, localDate, context.lifecycles())
                    .map(pending -> new ScheduledMotivationRow(
                            pending,
                            userId,
                            timezone,
                            localDate
                    ))
                    .ifPresent(rows::add);
        }
        return List.copyOf(rows);
    }

    private Optional<PendingMotivationProjection> pendingRow(
            UserChallenge membership,
            Challenge challenge,
            LocalDate localDate,
            Map<String, ChallengeCrewLifecycle> lifecycles
    ) {
        if (!eligibleMembership(membership) || !eligibleChallenge(challenge, lifecycles)) {
            return Optional.empty();
        }
        int totalDays = challenge.getNumberOfDays() == null ? 0 : challenge.getNumberOfDays();
        if (totalDays < 1 || challenge.getStartDate() == null) {
            return Optional.empty();
        }
        LocalDate lastDay = challenge.getStartDate().plusDays(totalDays - 1L);
        if (localDate.isBefore(challenge.getStartDate()) || localDate.isAfter(lastDay)) {
            return Optional.empty();
        }
        int dayNumber = Math.toIntExact(
                java.time.temporal.ChronoUnit.DAYS.between(challenge.getStartDate(), localDate) + 1L
        );
        List<Submission> submissions = submissions(
                membership.getUserId(), challenge.getId()
        );
        boolean submittedToday = submissions.stream()
                .anyMatch(submission -> Boolean.TRUE.equals(submission.getActive())
                        && Integer.valueOf(dayNumber).equals(submission.getDayNumber()));
        if (submittedToday) {
            return Optional.empty();
        }
        int completedDays = Math.toIntExact(submissions.stream()
                .filter(submission -> Boolean.TRUE.equals(submission.getActive()))
                .map(Submission::getDayNumber)
                .filter(day -> day != null && day >= 1 && day <= totalDays)
                .distinct()
                .count());

        boolean privateChallenge = Boolean.TRUE.equals(challenge.getIsPrivate());
        Long rank = null;
        Long totalParticipants = null;
        if (privateChallenge) {
            List<LeaderboardEntry> activeEntries = activeLeaderboard(challenge.getId());
            totalParticipants = (long) activeEntries.size();
            Optional<LeaderboardEntry> self = activeEntries.stream()
                    .filter(entry -> entry.getUser() != null
                            && membership.getUserId().equals(entry.getUser().getId()))
                    .findFirst();
            if (self.isPresent()) {
                double selfScore = score(self.get());
                rank = activeEntries.stream().filter(entry -> score(entry) > selfScore).count() + 1L;
            }
        }
        return Optional.of(new PendingMotivationRow(
                challenge.getId(),
                challenge.getTitle(),
                challenge.getStartDate(),
                totalDays,
                Optional.ofNullable(challenge.getChallengeMode()).orElse(Challenge.MODE_DAILY),
                true,
                Boolean.TRUE.equals(membership.getCompleted()),
                dayNumber,
                completedDays,
                privateChallenge,
                privateChallenge && !started(lifecycles.get(challenge.getId())),
                rank,
                totalParticipants
        ));
    }

    private CandidateContext context(Collection<UserChallenge> memberships) {
        List<String> userIds = memberships.stream()
                .map(UserChallenge::getUserId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        List<String> challengeIds = memberships.stream()
                .map(UserChallenge::getChallengeId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        Map<String, User> users = new LinkedHashMap<>();
        store.batchGet("USER", userIds, User.class, true)
                .forEach(user -> users.put(user.getId(), user));
        Map<String, Challenge> challenges = new LinkedHashMap<>();
        store.batchGet("CHALLENGE", challengeIds, Challenge.class, true)
                .forEach(challenge -> challenges.put(challenge.getId(), challenge));
        Map<String, ChallengeCrewLifecycle> lifecycles = new LinkedHashMap<>();
        store.batchGet(
                "CHALLENGE_CREW_LIFECYCLE",
                challengeIds,
                ChallengeCrewLifecycle.class,
                true
        ).forEach(lifecycle -> lifecycles.put(lifecycle.getChallengeId(), lifecycle));
        return new CandidateContext(users, challenges, lifecycles);
    }

    private List<UserChallenge> activeMembershipsForUser(String userId) {
        return store.queryAll(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.membershipStatusPrefix("ACTIVE") + '#',
                MAX_PARTITION_ITEMS,
                false,
                UserChallenge.class,
                true
        );
    }

    private List<UserChallenge> reminderCandidates() {
        Map<String, UserChallenge> distinct = new LinkedHashMap<>();
        for (int shard = 0; shard < ChallengeRepositoryKeys.REMINDER_SHARDS; shard++) {
            String partition = ChallengeRepositoryKeys.join(
                    "REMINDER", "SHARD", String.format(java.util.Locale.ROOT, "%02d", shard)
            );
            store.queryAll(
                    partition,
                    "CANDIDATE#",
                    MAX_PARTITION_ITEMS,
                    true,
                    UserChallenge.class,
                    true
            ).forEach(membership -> distinct.putIfAbsent(membership.getId(), membership));
        }
        return List.copyOf(distinct.values());
    }

    private Optional<UserPreferences> preferences(String userId) {
        return store.queryAll(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.preferencesPrefix(),
                1,
                true,
                UserPreferences.class,
                true
        ).stream().findFirst();
    }

    private Optional<PushDeviceToken> latestToken(String userId, boolean enabledOnly) {
        return store.queryAll(
                enabledOnly
                        ? ChallengeRepositoryKeys.enabledPushTokensPk(userId)
                        : ChallengeRepositoryKeys.pushTokensPk(userId),
                ChallengeRepositoryKeys.pushTokenPrefix(),
                1,
                true,
                PushDeviceToken.class,
                true
        ).stream().findFirst();
    }

    private List<Submission> submissions(String userId, String challengeId) {
        return store.queryAll(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.submissionChallengePrefix(challengeId),
                MAX_PARTITION_ITEMS,
                true,
                Submission.class,
                true
        );
    }

    private List<LeaderboardEntry> activeLeaderboard(String challengeId) {
        return store.queryAll(
                ChallengeRepositoryKeys.challengePk(challengeId),
                ChallengeRepositoryKeys.LEADERBOARD_ACTIVE_PREFIX,
                MAX_PARTITION_ITEMS,
                true,
                LeaderboardEntry.class,
                true
        );
    }

    private static boolean eligibleMembership(UserChallenge membership) {
        return membership != null
                && Boolean.TRUE.equals(membership.getActive())
                && !Boolean.TRUE.equals(membership.getBanned())
                && !Boolean.TRUE.equals(membership.getCompleted());
    }

    private static boolean eligibleChallenge(
            Challenge challenge,
            Map<String, ChallengeCrewLifecycle> lifecycles
    ) {
        if (challenge == null
                || !Boolean.TRUE.equals(challenge.getActive())
                || !Challenge.MODE_DAILY.equalsIgnoreCase(Optional.ofNullable(
                        challenge.getChallengeMode()
                ).orElse(Challenge.MODE_DAILY))) {
            return false;
        }
        return !Boolean.TRUE.equals(challenge.getIsPrivate())
                || started(lifecycles.get(challenge.getId()));
    }

    private static boolean activeUser(User user) {
        return user != null && !Boolean.FALSE.equals(user.getActive());
    }

    private static boolean legacyPushEnabled(User user) {
        int notificationMask = user == null || user.getNotifications() == null
                ? LEGACY_PUSH_NOTIFICATION_BIT
                : user.getNotifications();
        return (notificationMask & LEGACY_PUSH_NOTIFICATION_BIT) == LEGACY_PUSH_NOTIFICATION_BIT;
    }

    private static boolean started(ChallengeCrewLifecycle lifecycle) {
        return lifecycle != null && lifecycle.getStartedAt() != null;
    }

    private static double score(LeaderboardEntry entry) {
        return entry.getScore() == null ? 0.0d : entry.getScore();
    }

    private static LocalDate localDate(Instant instant, String timezone) {
        return instant.atZone(ZoneId.of(normalizedTimezone(timezone))).toLocalDate();
    }

    private static String normalizedTimezone(String timezone) {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "UTC" : timezone).getId();
        } catch (DateTimeException invalidTimezone) {
            return ZoneOffset.UTC.getId();
        }
    }

    private record CandidateContext(
            Map<String, User> users,
            Map<String, Challenge> challenges,
            Map<String, ChallengeCrewLifecycle> lifecycles
    ) {
    }

    public interface PendingMotivationProjection {
        String getChallengeId();
        String getChallengeTitle();
        LocalDate getStartDate();
        Integer getTotalDays();
        String getChallengeMode();
        Boolean getChallengeActive();
        Boolean getParticipantCompleted();
        Integer getDayNumber();
        Integer getCompletedDays();
        Boolean getPrivateChallenge();
        Boolean getPrivateLobby();
        Long getRank();
        Long getTotalParticipants();
    }

    public interface ScheduledMotivationProjection extends PendingMotivationProjection {
        String getUserId();
        String getTimezone();
        LocalDate getLocalDate();
    }

    public interface EveningUserProjection {
        String getUserId();
        String getTimezone();
    }

    private static class PendingMotivationRow implements PendingMotivationProjection {
        private final String challengeId;
        private final String challengeTitle;
        private final LocalDate startDate;
        private final Integer totalDays;
        private final String challengeMode;
        private final Boolean challengeActive;
        private final Boolean participantCompleted;
        private final Integer dayNumber;
        private final Integer completedDays;
        private final Boolean privateChallenge;
        private final Boolean privateLobby;
        private final Long rank;
        private final Long totalParticipants;

        private PendingMotivationRow(
                String challengeId,
                String challengeTitle,
                LocalDate startDate,
                Integer totalDays,
                String challengeMode,
                Boolean challengeActive,
                Boolean participantCompleted,
                Integer dayNumber,
                Integer completedDays,
                Boolean privateChallenge,
                Boolean privateLobby,
                Long rank,
                Long totalParticipants
        ) {
            this.challengeId = challengeId;
            this.challengeTitle = challengeTitle;
            this.startDate = startDate;
            this.totalDays = totalDays;
            this.challengeMode = challengeMode;
            this.challengeActive = challengeActive;
            this.participantCompleted = participantCompleted;
            this.dayNumber = dayNumber;
            this.completedDays = completedDays;
            this.privateChallenge = privateChallenge;
            this.privateLobby = privateLobby;
            this.rank = rank;
            this.totalParticipants = totalParticipants;
        }

        @Override public String getChallengeId() { return challengeId; }
        @Override public String getChallengeTitle() { return challengeTitle; }
        @Override public LocalDate getStartDate() { return startDate; }
        @Override public Integer getTotalDays() { return totalDays; }
        @Override public String getChallengeMode() { return challengeMode; }
        @Override public Boolean getChallengeActive() { return challengeActive; }
        @Override public Boolean getParticipantCompleted() { return participantCompleted; }
        @Override public Integer getDayNumber() { return dayNumber; }
        @Override public Integer getCompletedDays() { return completedDays; }
        @Override public Boolean getPrivateChallenge() { return privateChallenge; }
        @Override public Boolean getPrivateLobby() { return privateLobby; }
        @Override public Long getRank() { return rank; }
        @Override public Long getTotalParticipants() { return totalParticipants; }
    }

    private static final class ScheduledMotivationRow
            extends PendingMotivationRow implements ScheduledMotivationProjection {
        private final String userId;
        private final String timezone;
        private final LocalDate localDate;

        private ScheduledMotivationRow(
                PendingMotivationProjection pending,
                String userId,
                String timezone,
                LocalDate localDate
        ) {
            super(
                    pending.getChallengeId(), pending.getChallengeTitle(), pending.getStartDate(),
                    pending.getTotalDays(), pending.getChallengeMode(), pending.getChallengeActive(),
                    pending.getParticipantCompleted(), pending.getDayNumber(), pending.getCompletedDays(),
                    pending.getPrivateChallenge(), pending.getPrivateLobby(), pending.getRank(),
                    pending.getTotalParticipants()
            );
            this.userId = userId;
            this.timezone = timezone;
            this.localDate = localDate;
        }

        @Override public String getUserId() { return userId; }
        @Override public String getTimezone() { return timezone; }
        @Override public LocalDate getLocalDate() { return localDate; }
    }

    private record EveningUserRow(String userId, String timezone) implements EveningUserProjection {
        @Override public String getUserId() { return userId; }
        @Override public String getTimezone() { return timezone; }
    }
}
