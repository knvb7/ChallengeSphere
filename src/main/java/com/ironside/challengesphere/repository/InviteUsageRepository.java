package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.InviteUsage;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class InviteUsageRepository extends AbstractChallengeDynamoRepository<InviteUsage> {

    private static final Comparator<InviteUsage> RECENT_FIRST = Comparator
            .comparing(InviteUsage::getJoinedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(InviteUsage::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    private final Clock clock;

    public InviteUsageRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            Clock clock
    ) {
        super(store, writer, leases, "INVITE_USAGE", InviteUsage.class);
        this.clock = clock;
    }

    public boolean existsByInviteIdAndUserId(String inviteId, String userId) {
        return findByInviteIdAndUserId(inviteId, userId).isPresent();
    }

    public Optional<InviteUsage> findByInviteIdAndUserId(String inviteId, String userId) {
        if (blank(inviteId) || blank(userId)) {
            return Optional.empty();
        }
        return findUnique(ChallengeRepositoryKeys.inviteUsageUniquePk(inviteId, userId));
    }

    public List<InviteUsage> findByInviteId(String inviteId) {
        return queryProjectionPartition(
                ChallengeRepositoryKeys.inviteUsagePk(inviteId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ).stream().sorted(RECENT_FIRST).toList();
    }

    public List<InviteUsage> findByUserId(String userId) {
        if (blank(userId)) {
            return List.of();
        }
        return queryProjectionPartition(
                ChallengeRepositoryKeys.userPk(userId),
                "INVITE_USAGE#",
                false
        ).stream().sorted(RECENT_FIRST).toList();
    }

    @Override
    protected void prepareForSave(InviteUsage usage, boolean creating) {
        if (usage.getJoinedAt() == null) {
            usage.setJoinedAt(Timestamp.from(clock.instant()));
        }
    }

    @Override
    protected String id(InviteUsage usage) {
        return usage.getId();
    }

    @Override
    protected void assignId(InviteUsage usage, String id) {
        usage.setId(id);
    }

    @Override
    protected String saveLockKey(InviteUsage usage) {
        return blank(usage.getInviteId()) || blank(usage.getUserId())
                ? usage.getId()
                : ChallengeRepositoryKeys.join(
                        "INVITE", usage.getInviteId(), "USER", usage.getUserId()
                );
    }

    @Override
    protected Collection<DynamoProjection> uniqueProjections(InviteUsage usage) {
        if (blank(usage.getInviteId()) || blank(usage.getUserId())) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                ChallengeRepositoryKeys.inviteUsageUniquePk(usage.getInviteId(), usage.getUserId()),
                ChallengeRepositoryKeys.uniqueSk()
        ));
    }

    @Override
    protected Collection<DynamoProjection> projections(InviteUsage usage) {
        String id = ChallengeRepositoryKeys.require(usage.getId(), "usage.id");
        String inviteId = ChallengeRepositoryKeys.require(usage.getInviteId(), "usage.inviteId");
        String userId = ChallengeRepositoryKeys.require(usage.getUserId(), "usage.userId");
        String createdSk = ChallengeRepositoryKeys.createdSk(usage.getJoinedAt(), id);
        return List.of(
                DynamoProjection.of(ChallengeRepositoryKeys.inviteUsagePk(inviteId), createdSk),
                DynamoProjection.of(
                        ChallengeRepositoryKeys.userPk(userId),
                        ChallengeRepositoryKeys.join("INVITE_USAGE", ChallengeRepositoryKeys.epoch(
                                usage.getJoinedAt()
                        ), id)
                )
        );
    }

    List<DynamoProjection> projectionsForAtomicWrite(InviteUsage usage) {
        return allProjections(usage);
    }

    List<DynamoProjection> uniqueProjectionsForAtomicWrite(InviteUsage usage) {
        return List.copyOf(uniqueProjections(usage));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
