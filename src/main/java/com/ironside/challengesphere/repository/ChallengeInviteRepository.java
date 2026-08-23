package com.ironside.challengesphere.repository;

import com.ironside.challengesphere.model.ChallengeInvite;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoDomainStore;
import com.ironside.challengesphere.persistence.dynamodb.domain.DynamoPage;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Repository
@ConditionalOnProperty(prefix = "app.persistence", name = "mode", havingValue = "dynamodb")
public class ChallengeInviteRepository extends AbstractChallengeDynamoRepository<ChallengeInvite> {

    private static final Comparator<ChallengeInvite> RECENT_FIRST = Comparator
            .comparing(ChallengeInvite::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ChallengeInvite::getId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .reversed();

    private final Clock clock;

    public ChallengeInviteRepository(
            DynamoDomainStore store,
            ChallengeTransactionalWriter writer,
            ConditionalKeyLease leases,
            Clock clock
    ) {
        super(store, writer, leases, "CHALLENGE_INVITE", ChallengeInvite.class);
        this.clock = clock;
    }

    public Optional<ChallengeInvite> findByInviteCode(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            return Optional.empty();
        }
        return findUnique(ChallengeRepositoryKeys.inviteCodePk(inviteCode));
    }

    public InviteCodeLease acquireInviteCodeLease(String inviteCode) {
        String lockKey = codeLock(inviteCode);
        leases.acquire(entityType(), lockKey);
        return new InviteCodeLease(lockKey);
    }

    boolean isInviteCodeLeaseHeld(String inviteCode) {
        return leases.isHeld(entityType(), codeLock(inviteCode));
    }

    List<DynamoProjection> projectionsForAtomicWrite(ChallengeInvite invite) {
        return allProjections(invite);
    }

    List<DynamoProjection> uniqueProjectionsForAtomicWrite(ChallengeInvite invite) {
        return List.copyOf(uniqueProjections(invite));
    }

    public List<ChallengeInvite> findActiveByChallengeId(String challengeId) {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.join(
                        ChallengeRepositoryKeys.inviteChallengePk(challengeId), "ACTIVE"
                ),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    public List<ChallengeInvite> findByChallengeId(String challengeId) {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.inviteChallengePk(challengeId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    public DynamoPage<ChallengeInvite> findByChallengeIdPage(
            String challengeId,
            int limit,
            Map<String, AttributeValue> exclusiveStartKey
    ) {
        return queryProjectionPage(
                ChallengeRepositoryKeys.inviteChallengePk(challengeId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                limit,
                exclusiveStartKey,
                false
        );
    }

    public List<ChallengeInvite> findByCreatedBy(String userId) {
        return recent(queryProjectionPartition(
                ChallengeRepositoryKeys.inviteCreatorPk(userId),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        ));
    }

    public int deactivateActiveByChallengeId(String challengeId) {
        return deactivate(findActiveByChallengeId(challengeId), ignored -> true);
    }

    public int deactivateUnboundActiveByChallengeId(String challengeId) {
        List<ChallengeInvite> candidates = queryProjectionPartition(
                ChallengeRepositoryKeys.join(
                        ChallengeRepositoryKeys.inviteChallengePk(challengeId), "UNBOUND_ACTIVE"
                ),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        );
        return deactivate(candidates, invite -> invite.getRecipientUserId() == null);
    }

    public int deactivateActiveByChallengeIdAndRecipientUserId(
            String challengeId,
            String recipientUserId
    ) {
        List<ChallengeInvite> candidates = queryProjectionPartition(
                ChallengeRepositoryKeys.join(
                        ChallengeRepositoryKeys.inviteChallengePk(challengeId),
                        "RECIPIENT", recipientUserId, "ACTIVE"
                ),
                ChallengeRepositoryKeys.CREATED_PREFIX,
                false
        );
        return deactivate(candidates, invite -> recipientUserId.equals(invite.getRecipientUserId()));
    }

    @Override
    protected void prepareForSave(ChallengeInvite invite, boolean creating) {
        if (invite.getCreatedAt() == null) {
            invite.setCreatedAt(Timestamp.from(clock.instant()));
        }
        if (invite.getActive() == null) {
            invite.setActive(true);
        }
        if (invite.getUseCount() == null) {
            invite.setUseCount(0);
        }
    }

    @Override
    protected String id(ChallengeInvite invite) {
        return invite.getId();
    }

    @Override
    protected void assignId(ChallengeInvite invite, String id) {
        invite.setId(id);
    }

    @Override
    protected String saveLockKey(ChallengeInvite invite) {
        return invite.getInviteCode() == null || invite.getInviteCode().isBlank()
                ? invite.getId()
                : codeLock(invite.getInviteCode());
    }

    @Override
    protected Collection<DynamoProjection> uniqueProjections(ChallengeInvite invite) {
        if (invite.getInviteCode() == null || invite.getInviteCode().isBlank()) {
            return List.of();
        }
        return List.of(DynamoProjection.of(
                ChallengeRepositoryKeys.inviteCodePk(invite.getInviteCode()),
                ChallengeRepositoryKeys.uniqueSk()
        ));
    }

    @Override
    protected Collection<DynamoProjection> projections(ChallengeInvite invite) {
        String id = ChallengeRepositoryKeys.require(invite.getId(), "invite.id");
        String challengeId = ChallengeRepositoryKeys.require(
                invite.getChallengeId(), "invite.challengeId"
        );
        String createdSk = ChallengeRepositoryKeys.createdSk(invite.getCreatedAt(), id);
        List<DynamoProjection> projections = new ArrayList<>();
        projections.add(DynamoProjection.of(
                ChallengeRepositoryKeys.inviteChallengePk(challengeId), createdSk
        ));
        if (invite.getCreatedBy() != null && !invite.getCreatedBy().isBlank()) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.inviteCreatorPk(invite.getCreatedBy()), createdSk
            ));
        }
        if (invite.getRecipientUserId() != null && !invite.getRecipientUserId().isBlank()) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.inviteRecipientPk(invite.getRecipientUserId()), createdSk
            ));
        }
        if (Boolean.TRUE.equals(invite.getActive())) {
            projections.add(DynamoProjection.of(
                    ChallengeRepositoryKeys.join(
                            ChallengeRepositoryKeys.inviteChallengePk(challengeId), "ACTIVE"
                    ),
                    createdSk
            ));
            if (invite.getRecipientUserId() == null || invite.getRecipientUserId().isBlank()) {
                projections.add(DynamoProjection.of(
                        ChallengeRepositoryKeys.join(
                                ChallengeRepositoryKeys.inviteChallengePk(challengeId), "UNBOUND_ACTIVE"
                        ),
                        createdSk
                ));
            } else {
                projections.add(DynamoProjection.of(
                        ChallengeRepositoryKeys.join(
                                ChallengeRepositoryKeys.inviteChallengePk(challengeId),
                                "RECIPIENT", invite.getRecipientUserId(), "ACTIVE"
                        ),
                        createdSk
                ));
            }
        }
        return projections;
    }

    private int deactivate(Collection<ChallengeInvite> candidates, Predicate<ChallengeInvite> predicate) {
        int changed = 0;
        for (ChallengeInvite candidate : candidates) {
            String lockKey = saveLockKey(candidate);
            leases.acquire(entityType(), lockKey);
            try {
                Optional<ChallengeInvite> latest = findById(candidate.getId());
                if (latest.isEmpty()) {
                    continue;
                }
                ChallengeInvite invite = latest.get();
                if (!Boolean.TRUE.equals(invite.getActive()) || !predicate.test(invite)) {
                    continue;
                }
                invite.setActive(false);
                save(invite);
                changed++;
            } finally {
                leases.release(entityType(), lockKey);
            }
        }
        return changed;
    }

    private static List<ChallengeInvite> recent(Collection<ChallengeInvite> invites) {
        return invites.stream().sorted(RECENT_FIRST).toList();
    }

    private static String codeLock(String inviteCode) {
        return ChallengeRepositoryKeys.join("CODE", ChallengeRepositoryKeys.normalize(inviteCode));
    }

    public final class InviteCodeLease implements AutoCloseable {
        private final String lockKey;
        private boolean closed;

        private InviteCodeLease(String lockKey) {
            this.lockKey = lockKey;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            leases.release(entityType(), lockKey);
        }
    }
}
