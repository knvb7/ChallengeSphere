package com.ironside.challengesphere.scheduler;

import com.ironside.challengesphere.model.Challenge;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.model.UserChallenge;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore;
import com.ironside.challengesphere.persistence.dynamodb.operations.DynamoOperationalStore.Lease;
import com.ironside.challengesphere.persistence.dynamodb.operations.OfficialChallengeCatalog;
import com.ironside.challengesphere.persistence.dynamodb.operations.OfficialChallengeCatalog.Template;
import com.ironside.challengesphere.repository.ChallengeRepository;
import com.ironside.challengesphere.repository.UserChallengeRepository;
import com.ironside.challengesphere.repository.UserRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static java.time.DayOfWeek.MONDAY;

@Component
@ConditionalOnProperty(
        prefix = "app.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class OfficialGlobalCohortScheduler {

    private static final Logger logger = LogManager.getLogger(OfficialGlobalCohortScheduler.class);
    private static final String OFFICIAL_EMAIL = "official-global@challengesphere.app";
    private static final String OFFICIAL_USERNAME = "challengesphere_official";
    private static final String OFFICIAL_BIO = "Official ChallengeSphere weekly cohorts";
    private static final String NO_LOGIN_PASSWORD = "OFFICIAL_ACCOUNT_NO_LOGIN";
    private static final String PUBLISH_LEASE_SCOPE = "OFFICIAL_GLOBAL_COHORTS";
    private static final String PUBLISH_LEASE_KEY = "ROLLING_WEEKLY_COHORTS";
    private static final Duration PUBLISH_LEASE_DURATION = Duration.ofMinutes(15);
    private static final LocalDate ROTATION_ANCHOR = LocalDate.of(2026, 1, 5);

    private final UserRepository userRepository;
    private final ChallengeRepository challengeRepository;
    private final UserChallengeRepository userChallengeRepository;
    private final DynamoOperationalStore operationalStore;
    private final Clock clock;

    @Value("${app.global-cohorts.enabled:true}")
    private boolean enabled;

    public OfficialGlobalCohortScheduler(
            UserRepository userRepository,
            ChallengeRepository challengeRepository,
            UserChallengeRepository userChallengeRepository,
            DynamoOperationalStore operationalStore,
            Clock clock
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.challengeRepository = Objects.requireNonNull(challengeRepository, "challengeRepository");
        this.userChallengeRepository = Objects.requireNonNull(
                userChallengeRepository,
                "userChallengeRepository"
        );
        this.operationalStore = Objects.requireNonNull(operationalStore, "operationalStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishAtStartup() {
        publishCurrentCohorts();
    }

    @Scheduled(
            cron = "${app.global-cohorts.cron:0 10 0 * * *}",
            zone = "${app.global-cohorts.zone:UTC}"
    )
    public void publishCurrentCohorts() {
        if (!enabled) {
            logger.debug("Official global cohorts disabled, skipping");
            return;
        }

        Optional<Lease> lease = Optional.empty();
        try {
            lease = operationalStore.tryAcquireLease(
                    PUBLISH_LEASE_SCOPE,
                    PUBLISH_LEASE_KEY,
                    PUBLISH_LEASE_DURATION
            );
            if (lease.isEmpty()) {
                logger.debug("Official global cohort publication is already running");
                return;
            }
            int created = publish(LocalDate.now(clock));
            if (created > 0) {
                logger.info("Published {} official weekly global cohort(s)", created);
            }
        } catch (Exception exception) {
            // Each write is idempotently recoverable and the next daily run resumes
            // any partially completed publication under a fresh conditional lease.
            logger.error(
                    "Unable to publish official global cohorts: {}",
                    exception.getClass().getSimpleName()
            );
        } finally {
            lease.ifPresent(this::releaseLeaseSafely);
        }
    }

    private void releaseLeaseSafely(Lease lease) {
        try {
            operationalStore.releaseLease(lease);
        } catch (RuntimeException failure) {
            logger.warn(
                    "Unable to release official cohort publication lease: {}",
                    failure.getClass().getSimpleName()
            );
        }
    }

    private int publish(LocalDate asOfDate) {
        User official = findOrCreateOfficialUser();
        LocalDate weekStart = asOfDate.with(TemporalAdjusters.previousOrSame(MONDAY));
        List<Challenge> officialChallenges = new ArrayList<>(
                challengeRepository.findByCreatorId(official.getId())
        );
        Map<String, Challenge> byCohortKey = retireExpiredAndIndex(
                officialChallenges,
                weekStart
        );

        List<Template> eligible = OfficialChallengeCatalog.eligibleOn(asOfDate);
        if (eligible.size() < 3) {
            throw new IllegalStateException(
                    "At least three enabled official challenge templates are required"
            );
        }

        long elapsedWeeks = ChronoUnit.WEEKS.between(ROTATION_ANCHOR, weekStart);
        int rotationOffset = (int) Math.floorMod(elapsedWeeks * 3L, eligible.size());
        int created = 0;
        for (int index = 0; index < 3; index++) {
            Template template = eligible.get((rotationOffset + index) % eligible.size());
            String cohortKey = template.key() + ':' + weekStart;
            Challenge cohort = byCohortKey.get(cohortKey);
            if (cohort == null) {
                cohort = createCohort(official, template, cohortKey, weekStart);
                byCohortKey.put(cohortKey, cohort);
                created++;
            }
            ensureOfficialMembership(official.getId(), cohort.getId());
        }

        int activeCreated = (int) challengeRepository.findByCreatorId(official.getId()).stream()
                .filter(challenge -> Boolean.TRUE.equals(challenge.getActive()))
                .count();
        official.setChallengesCreated(activeCreated);
        userRepository.save(official);
        return created;
    }

    private User findOrCreateOfficialUser() {
        Optional<User> byUsername = userRepository.findByUsername(OFFICIAL_USERNAME);
        Optional<User> byEmail = userRepository.findByEmailId(OFFICIAL_EMAIL);
        if (byUsername.isPresent() && byEmail.isPresent()
                && !Objects.equals(byUsername.get().getId(), byEmail.get().getId())) {
            throw reservedIdentifierConflict();
        }

        User official = byUsername.or(() -> byEmail).orElseGet(() -> {
            User user = new User();
            user.setEmailId(OFFICIAL_EMAIL);
            user.setUsername(OFFICIAL_USERNAME);
            user.setHashedPassword(NO_LOGIN_PASSWORD);
            user.setActive(true);
            user.setBio(OFFICIAL_BIO);
            return user;
        });
        if (!OFFICIAL_USERNAME.equals(normalize(official.getUsername()))
                || !OFFICIAL_EMAIL.equals(normalize(official.getEmailId()))) {
            throw reservedIdentifierConflict();
        }

        official.setActive(true);
        official.setBio(OFFICIAL_BIO);
        return userRepository.save(official);
    }

    private Map<String, Challenge> retireExpiredAndIndex(
            List<Challenge> officialChallenges,
            LocalDate weekStart
    ) {
        Map<String, Challenge> byCohortKey = new LinkedHashMap<>();
        for (Challenge challenge : officialChallenges) {
            boolean legacyPublic = Boolean.TRUE.equals(challenge.getActive())
                    && !Boolean.TRUE.equals(challenge.getIsPrivate())
                    && !hasText(challenge.getCohortKey());
            boolean expiredCohort = Boolean.TRUE.equals(challenge.getActive())
                    && hasText(challenge.getCohortKey())
                    && challenge.getStartDate() != null
                    && challenge.getNumberOfDays() != null
                    && !challenge.getStartDate()
                    .plusDays(challenge.getNumberOfDays())
                    .isAfter(weekStart);
            if (legacyPublic || expiredCohort) {
                challengeRepository.mutateExisting(
                        challenge.getId(),
                        latest -> latest.setActive(false)
                );
            }
            if (hasText(challenge.getCohortKey())) {
                byCohortKey.putIfAbsent(challenge.getCohortKey(), challenge);
            }
        }
        return byCohortKey;
    }

    private Challenge createCohort(
            User official,
            Template template,
            String cohortKey,
            LocalDate weekStart
    ) {
        Challenge challenge = new Challenge();
        challenge.setHashtags("official,cohort," + template.hashtags());
        challenge.setCategories(template.categories());
        challenge.setTitle(template.title());
        challenge.setDescription(
                template.description() + "\n\nRules & Guidelines: " + template.rules()
        );
        challenge.setBenefits(template.benefits());
        challenge.setIsPrivate(false);
        challenge.setStartDate(weekStart);
        challenge.setActive(true);
        challenge.setCreatedAt(Timestamp.from(clock.instant()));
        challenge.setCreatorId(official.getId());
        challenge.setCurrentOwnerId(official.getId());
        challenge.setNumberOfDays(Math.min(template.numberOfDays(), 7));
        challenge.setChallengeMode(Challenge.MODE_DAILY);
        challenge.setProofMode(template.proofMode());
        challenge.setOfficialTemplateKey(template.key());
        challenge.setCohortKey(cohortKey);
        return challengeRepository.save(challenge);
    }

    private void ensureOfficialMembership(String officialUserId, String challengeId) {
        Optional<UserChallenge> existing = userChallengeRepository.findByUserIdAndChallengeId(
                officialUserId,
                challengeId
        );
        if (existing.isPresent()) {
            UserChallenge membership = existing.get();
            if (!Boolean.TRUE.equals(membership.getActive())) {
                membership.setActive(true);
                userChallengeRepository.save(membership);
            }
            return;
        }

        UserChallenge membership = new UserChallenge();
        membership.setUserId(officialUserId);
        membership.setChallengeId(challengeId);
        membership.setJoinedAt(Timestamp.from(clock.instant()));
        membership.setCompleted(false);
        membership.setActive(true);
        userChallengeRepository.save(membership);
    }

    private static IllegalStateException reservedIdentifierConflict() {
        return new IllegalStateException(
                "Reserved official ChallengeSphere account identifiers are already in use"
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
