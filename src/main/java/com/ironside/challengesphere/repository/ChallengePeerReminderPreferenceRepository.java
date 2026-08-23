package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengePeerReminderPreference;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.AbstractChallengeDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeTransactionalWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ConditionalKeyLease;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ChallengePeerReminderPreferenceRepository
        extends AbstractChallengeDynamoRepository<ChallengePeerReminderPreference> {

    private final Clock clock;

    public ChallengePeerReminderPreferenceRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            Clock clock
    ) {
        super(store, writer, leases, "CHALLENGE_PEER_REMINDER_PREFERENCE",
                ChallengePeerReminderPreference.class);
        this.clock = clock;
    }

    public Optional<ChallengePeerReminderPreference> findByChallengeIdAndUserId(
            String challengeId,
            String userId
    ) {
        if (blank(challengeId) || blank(userId)) {
            return Optional.empty();
        }
        return findUnique(ChallengeRepositoryKeys.reminderPreferenceUniquePk(challengeId, userId));
    }

    public List<ChallengePeerReminderPreference> findByChallengeIdAndEnabledTrue(
            String challengeId
    ) {
        return queryProjectionPartition(
                ChallengeRepositoryKeys.join(
                        ChallengeRepositoryKeys.reminderPreferencePk(challengeId), "ENABLED"
                ),
                "USER#",
                true
        );
    }

    public List<ChallengePeerReminderPreference> findByUserId(String userId) {
        if (blank(userId)) {
            return List.of();
        }
        return queryProjectionPartition(
                ChallengeRepositoryKeys.userPk(userId),
                "PEER_REMINDER#",
                true
        );
    }

    public ConditionalKeyLease.Lease acquirePreferenceLease(
            String challengeId,
            String userId
    ) {
        if (blank(challengeId) || blank(userId)) {
            throw new IllegalArgumentException("challengeId and userId must not be blank");
        }
        return leases.acquireLease(entityType(), compositeLock(challengeId, userId));
    }

    @Override
    protected void prepareForSave(ChallengePeerReminderPreference preference, boolean creating) {
        Timestamp now = Timestamp.from(clock.instant());
        if (preference.getCreatedAt() == null) {
            preference.setCreatedAt(now);
        }
        preference.setUpdatedAt(now);
        if (preference.getEnabled() == null) {
            preference.setEnabled(false);
        }
    }

    @Override
    protected String id(ChallengePeerReminderPreference preference) {
        return preference.getId();
    }

    @Override
    protected void assignId(ChallengePeerReminderPreference preference, String id) {
        preference.setId(id);
    }

    @Override
    protected String saveLockKey(ChallengePeerReminderPreference preference) {
        return blank(preference.getChallengeId()) || blank(preference.getUserId())
                ? preference.getId()
                : compositeLock(preference.getChallengeId(), preference.getUserId());
    }

    @Override
    protected Collection<DynamoProjection> uniqueProjections(
            ChallengePeerReminderPreference preference
    ) {
        if (blank(preference.getChallengeId()) || blank(preference.getUserId())) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                ChallengeRepositoryKeys.reminderPreferenceUniquePk(
                        preference.getChallengeId(), preference.getUserId()
                ),
                ChallengeRepositoryKeys.uniqueSk()
        ));
    }

    @Override
    protected Collection<DynamoProjection> projections(
            ChallengePeerReminderPreference preference
    ) {
        String id = ChallengeRepositoryKeys.require(preference.getId(), "preference.id");
        String challengeId = ChallengeRepositoryKeys.require(
                preference.getChallengeId(), "preference.challengeId"
        );
        String userId = ChallengeRepositoryKeys.require(
                preference.getUserId(), "preference.userId"
        );
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.reminderPreferencePk(challengeId),
                ChallengeRepositoryKeys.join("USER", userId, id)
        ));
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.userPk(userId),
                ChallengeRepositoryKeys.join("PEER_REMINDER", challengeId, id)
        ));
        if (Boolean.TRUE.equals(preference.getEnabled())) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.join(
                            ChallengeRepositoryKeys.reminderPreferencePk(challengeId), "ENABLED"
                    ),
                    ChallengeRepositoryKeys.join("USER", userId, id)
            ));
        }
        return projections;
    }

    private static String compositeLock(String challengeId, String userId) {
        return ChallengeRepositoryKeys.join("CHALLENGE", challengeId, "USER", userId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
