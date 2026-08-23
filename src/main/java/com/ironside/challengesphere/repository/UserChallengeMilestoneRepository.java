package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.UserChallengeMilestone;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoProjection;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.AbstractChallengeDynamoRepository;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeRepositoryKeys;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ChallengeTransactionalWriter;
import com.ironside.challengesphere.persistence.dynamodb.repository.challenge.ConditionalKeyLease;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class UserChallengeMilestoneRepository
        extends AbstractChallengeDynamoRepository<UserChallengeMilestone> {

    private final Clock clock;

    public UserChallengeMilestoneRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            Clock clock
    ) {
        super(store, writer, leases, "USER_CHALLENGE_MILESTONE", UserChallengeMilestone.class);
        this.clock = clock;
    }

    public int claim(String userId, String challengeId, String milestoneCode) {
        if (blank(userId) || blank(challengeId) || blank(milestoneCode)) {
            throw new IllegalArgumentException("milestone claim fields must not be blank");
        }
        UserChallengeMilestone milestone = new UserChallengeMilestone();
        milestone.setUserId(userId);
        milestone.setChallengeId(challengeId);
        milestone.setMilestoneCode(milestoneCode);
        try {
            save(milestone);
            return 1;
        } catch (DataIntegrityViolationException duplicate) {
            return 0;
        }
    }

    public int attachNotification(
            String userId,
            String challengeId,
            String milestoneCode,
            String notificationId
    ) {
        if (blank(userId) || blank(challengeId) || blank(milestoneCode)) {
            return 0;
        }
        String lockKey = compositeLock(userId, challengeId, milestoneCode);
        try (ConditionalKeyLease.Lease ignored = leases.acquireLease(entityType(), lockKey)) {
            Optional<UserChallengeMilestone> existing = findUnique(
                    ChallengeRepositoryKeys.milestoneUniquePk(userId, challengeId, milestoneCode)
            );
            if (existing.isEmpty()) {
                return 0;
            }
            existing.get().setNotificationId(notificationId);
            save(existing.get());
            return 1;
        }
    }

    @Override
    protected void prepareForSave(UserChallengeMilestone milestone, boolean creating) {
        if (milestone.getClaimedAt() == null) {
            milestone.setClaimedAt(Timestamp.from(clock.instant()));
        }
    }

    @Override
    protected String id(UserChallengeMilestone milestone) {
        return milestone.getId();
    }

    @Override
    protected void assignId(UserChallengeMilestone milestone, String id) {
        milestone.setId(id);
    }

    @Override
    protected String saveLockKey(UserChallengeMilestone milestone) {
        if (blank(milestone.getUserId())
                || blank(milestone.getChallengeId())
                || blank(milestone.getMilestoneCode())) {
            return milestone.getId();
        }
        return compositeLock(
                milestone.getUserId(), milestone.getChallengeId(), milestone.getMilestoneCode()
        );
    }

    @Override
    protected Collection<DynamoProjection> uniqueProjections(UserChallengeMilestone milestone) {
        if (blank(milestone.getUserId())
                || blank(milestone.getChallengeId())
                || blank(milestone.getMilestoneCode())) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                ChallengeRepositoryKeys.milestoneUniquePk(
                        milestone.getUserId(),
                        milestone.getChallengeId(),
                        milestone.getMilestoneCode()
                ),
                ChallengeRepositoryKeys.uniqueSk()
        ));
    }

    @Override
    protected Collection<DynamoProjection> projections(UserChallengeMilestone milestone) {
        String id = ChallengeRepositoryKeys.require(milestone.getId(), "milestone.id");
        String userId = ChallengeRepositoryKeys.require(milestone.getUserId(), "milestone.userId");
        String challengeId = ChallengeRepositoryKeys.require(
                milestone.getChallengeId(), "milestone.challengeId"
        );
        String code = ChallengeRepositoryKeys.normalize(
                ChallengeRepositoryKeys.require(milestone.getMilestoneCode(), "milestone.code")
        );
        String sk = ChallengeRepositoryKeys.join(
                "MILESTONE", challengeId, code,
                ChallengeRepositoryKeys.epoch(milestone.getClaimedAt()), id
        );
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(DynamoProjection.of(ChallengeRepositoryKeys.userPk(userId), sk));
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.challengePk(challengeId),
                ChallengeRepositoryKeys.join(
                        "MILESTONE", userId, code,
                        ChallengeRepositoryKeys.epoch(milestone.getClaimedAt()), id
                )
        ));
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.milestonePk(userId, challengeId),
                ChallengeRepositoryKeys.join("CODE", code, id)
        ));
        return projections;
    }

    private static String compositeLock(String userId, String challengeId, String code) {
        return ChallengeRepositoryKeys.join(
                "USER", userId, "CHALLENGE", challengeId,
                "CODE", ChallengeRepositoryKeys.normalize(code)
        );
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
