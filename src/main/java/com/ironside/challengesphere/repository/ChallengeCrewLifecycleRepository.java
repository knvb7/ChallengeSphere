package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengeCrewLifecycle;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ChallengeCrewLifecycleRepository
        extends AbstractChallengeDynamoRepository<ChallengeCrewLifecycle> {

    private final Clock clock;

    public ChallengeCrewLifecycleRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            Clock clock
    ) {
        super(store, writer, leases, "CHALLENGE_CREW_LIFECYCLE", ChallengeCrewLifecycle.class);
        this.clock = clock;
    }

    public ConditionalKeyLease.Lease acquireChallengeLease(String challengeId) {
        return leases.acquireLease(
                entityType(),
                ChallengeRepositoryKeys.require(challengeId, "challengeId")
        );
    }

    public Optional<ChallengeCrewLifecycle> findByRematchOfChallengeId(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return Optional.empty();
        }
        return findUnique(ChallengeRepositoryKeys.lifecycleRematchPk(challengeId));
    }

    public List<ChallengeCrewLifecycle> findByStartedByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return queryProjectionPartition(
                ChallengeRepositoryKeys.userPk(userId),
                "CREW_LIFECYCLE_STARTED#",
                true
        );
    }

    @Override
    protected void prepareForSave(ChallengeCrewLifecycle lifecycle, boolean creating) {
        Timestamp now = Timestamp.from(clock.instant());
        if (lifecycle.getCreatedAt() == null) {
            lifecycle.setCreatedAt(now);
        }
        lifecycle.setUpdatedAt(now);
    }

    @Override
    protected String id(ChallengeCrewLifecycle lifecycle) {
        return lifecycle.getChallengeId();
    }

    @Override
    protected void assignId(ChallengeCrewLifecycle lifecycle, String id) {
        lifecycle.setChallengeId(id);
    }

    @Override
    protected Collection<DynamoProjection> uniqueProjections(ChallengeCrewLifecycle lifecycle) {
        if (lifecycle.getRematchOfChallengeId() == null
                || lifecycle.getRematchOfChallengeId().isBlank()) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                ChallengeRepositoryKeys.lifecycleRematchPk(lifecycle.getRematchOfChallengeId()),
                ChallengeRepositoryKeys.uniqueSk()
        ));
    }

    @Override
    protected Collection<DynamoProjection> projections(ChallengeCrewLifecycle lifecycle) {
        String challengeId = ChallengeRepositoryKeys.require(
                lifecycle.getChallengeId(), "lifecycle.challengeId"
        );
        java.util.ArrayList<DynamoProjection> projections = new java.util.ArrayList<>();
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.challengePk(challengeId), "CREW_LIFECYCLE#META"
        ));
        if (lifecycle.getStartedByUserId() != null && !lifecycle.getStartedByUserId().isBlank()) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.userPk(lifecycle.getStartedByUserId()),
                    "CREW_LIFECYCLE_STARTED#" + challengeId
            ));
        }
        if (lifecycle.getRematchOfChallengeId() != null
                && !lifecycle.getRematchOfChallengeId().isBlank()) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.lifecycleRematchPk(lifecycle.getRematchOfChallengeId()),
                    ChallengeRepositoryKeys.itemSk(challengeId)
            ));
        }
        return List.copyOf(projections);
    }
}
